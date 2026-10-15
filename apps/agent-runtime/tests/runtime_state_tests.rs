//! M5 5d: Runtime-owned internal state (StepRun/Checkpoint/evidence rechecks), durable recovery
//! after a restart, at-least-once cross-store delivery and the disclose-only reconciliation.
use chrono::{DateTime, Duration, Utc};
use opsweave_agent_runtime::{
    adapters::journal::JournalRuntimeStateStore, domain::AirunStatus, error::AppError,
    ports::RuntimeStateStore, runtime_state::*,
};
use std::path::PathBuf;
use uuid::Uuid;

fn t(seconds: i64) -> DateTime<Utc> {
    "2026-10-14T01:00:00Z".parse::<DateTime<Utc>>().unwrap() + Duration::seconds(seconds)
}
fn journal_path() -> PathBuf {
    std::env::temp_dir().join(format!("opsweave-runtime-state-{}.jsonl", Uuid::new_v4()))
}
fn step(run: &str, attempt: u32, step: StepKind, status: StepStatus, at: DateTime<Utc>) -> StepRun {
    match status {
        StepStatus::Running => StepRun::new(run, attempt, step, status, at, None, None).unwrap(),
        StepStatus::Succeeded => StepRun::new(
            run,
            attempt,
            step,
            status,
            at,
            Some(at + Duration::seconds(1)),
            None,
        )
        .unwrap(),
        StepStatus::Failed => StepRun::new(
            run,
            attempt,
            step,
            status,
            at,
            Some(at + Duration::seconds(1)),
            Some("EXECUTION_FAILED".into()),
        )
        .unwrap(),
    }
}
fn checkpoint(run: &str, attempt: u32, seq: u64, at: DateTime<Utc>) -> Checkpoint {
    Checkpoint::new(
        run,
        attempt,
        seq,
        StepKind::ContextBuild,
        at,
        vec!["evidence-1".into()],
        vec!["MISSING_METRIC_WINDOW".into()],
        at + Duration::seconds(1),
    )
    .unwrap()
}
fn recheck(run: &str, attempt: u32, at: DateTime<Utc>) -> EvidenceRecheck {
    EvidenceRecheck::new(
        run,
        attempt,
        "evidence-1",
        at,
        at - Duration::minutes(5),
        at - Duration::minutes(5),
        at + Duration::minutes(5),
        at,
    )
    .unwrap()
}
fn report(run: &str, attempt: u32, key: &str, at: DateTime<Utc>) -> RuntimeStateReport {
    RuntimeStateReport::new(key, run, attempt, AirunStatus::Running, 2, 1, 1, at).unwrap()
}
fn platform_view(
    run: &str,
    status: AirunStatus,
    attempt: u32,
    updated_at: DateTime<Utc>,
) -> PlatformRunView {
    PlatformRunView {
        run_id: run.to_owned(),
        status,
        status_version: 4,
        attempt,
        updated_at,
    }
}
fn runtime_view(status: AirunStatus, attempt: u32, updated_at: DateTime<Utc>) -> RuntimeView {
    RuntimeView {
        status,
        attempt,
        checkpoint_seq: 1,
        step_count: 2,
        updated_at,
    }
}
fn codes(report: &ReconciliationReport) -> Vec<&'static str> {
    report.findings.iter().map(|f| f.code()).collect()
}

#[tokio::test]
async fn the_journal_recovers_every_recorded_step_after_a_restart() {
    let path = journal_path();
    {
        let store = JournalRuntimeStateStore::open(path.clone()).unwrap();
        let opened = store.open_run("run-1", "tenant-demo", t(0)).await.unwrap();
        assert_eq!(opened.attempt, 1);
        assert_eq!(opened.status, AirunStatus::Running);
        store
            .record_step(&step(
                "run-1",
                1,
                StepKind::ContextBuild,
                StepStatus::Succeeded,
                t(1),
            ))
            .await
            .unwrap();
        store
            .record_step(&step(
                "run-1",
                1,
                StepKind::ModelCall,
                StepStatus::Running,
                t(2),
            ))
            .await
            .unwrap();
        store
            .save_checkpoint(&checkpoint("run-1", 1, 1, t(2)))
            .await
            .unwrap();
        store
            .record_recheck(&recheck("run-1", 1, t(3)))
            .await
            .unwrap();
        store
            .set_status("run-1", AirunStatus::Succeeded, t(4))
            .await
            .unwrap();
    }
    // Restart: the same journal is replayed and the internal progress is back exactly as recorded.
    let reopened = JournalRuntimeStateStore::open(path.clone()).unwrap();
    assert_eq!(reopened.recovery().records_replayed, 6);
    assert_eq!(reopened.recovery().torn_tail_lines, 0);
    let state = reopened.state("run-1").await.unwrap().unwrap();
    assert_eq!(state.attempt, 1);
    assert_eq!(state.status, AirunStatus::Succeeded);
    assert_eq!(state.steps.len(), 2);
    assert_eq!(state.checkpoints.len(), 1);
    assert_eq!(state.rechecks.len(), 1);
    assert_eq!(state.latest_checkpoint().unwrap().seq, 1);
    // Recovery resumes on the same run as a new attempt —never a second run.
    let resumed = reopened
        .open_run("run-1", "tenant-demo", t(5))
        .await
        .unwrap();
    assert_eq!(resumed.attempt, 2);
    assert_eq!(resumed.status, AirunStatus::Running);
    assert_eq!(resumed.checkpoints.len(), 1);
    std::fs::remove_file(&path).ok();
}

#[tokio::test]
async fn a_torn_journal_tail_is_disclosed_and_a_corrupt_record_fails_closed() {
    let path = journal_path();
    {
        let store = JournalRuntimeStateStore::open(path.clone()).unwrap();
        store.open_run("run-1", "tenant-demo", t(0)).await.unwrap();
        store
            .record_step(&step(
                "run-1",
                1,
                StepKind::ContextBuild,
                StepStatus::Succeeded,
                t(1),
            ))
            .await
            .unwrap();
    }
    // A crash mid-write leaves a torn trailing line: it is counted and disclosed, not invented over.
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(&path)
        .unwrap();
    std::io::Write::write_all(&mut file, b"{\"kind\":\"ste").unwrap();
    std::io::Write::flush(&mut file).unwrap();
    drop(file);
    let reopened = JournalRuntimeStateStore::open(path.clone()).unwrap();
    assert_eq!(reopened.recovery().torn_tail_lines, 1);
    assert_eq!(reopened.recovery().records_replayed, 2);
    assert_eq!(
        reopened.state("run-1").await.unwrap().unwrap().steps.len(),
        1
    );
    drop(reopened);
    // A corrupt record before the tail is not skipped: the open fails instead of losing data quietly.
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(&path)
        .unwrap();
    std::io::Write::write_all(&mut file, b"not-a-record\n").unwrap();
    std::io::Write::write_all(
        &mut file,
        b"{\"kind\":\"step\",\"runId\":\"run-1\",\"attempt\":1,\"step\":\"model_call\",\"status\":\"succeeded\",\"startedAt\":\"2026-10-14T01:00:02Z\",\"finishedAt\":\"2026-10-14T01:00:03Z\",\"reason\":null}\n",
    )
    .unwrap();
    std::io::Write::flush(&mut file).unwrap();
    drop(file);
    assert!(JournalRuntimeStateStore::open(path.clone()).is_err());
    std::fs::remove_file(&path).ok();
}

#[tokio::test]
async fn internal_status_follows_the_closed_table_and_terminal_states_never_move() {
    let path = journal_path();
    let store = JournalRuntimeStateStore::open(path.clone()).unwrap();
    store.open_run("run-1", "tenant-demo", t(0)).await.unwrap();
    assert!(matches!(
        store.set_status("run-1", AirunStatus::Pending, t(1)).await,
        Err(AppError::Invalid(_))
    ));
    assert!(matches!(
        store.set_status("run-1", AirunStatus::Running, t(1)).await,
        Err(AppError::Invalid(_))
    ));
    store
        .set_status("run-1", AirunStatus::WaitingTool, t(1))
        .await
        .unwrap();
    store
        .set_status("run-1", AirunStatus::Running, t(2))
        .await
        .unwrap();
    store
        .set_status("run-1", AirunStatus::Succeeded, t(3))
        .await
        .unwrap();
    assert!(matches!(
        store.set_status("run-1", AirunStatus::Failed, t(4)).await,
        Err(AppError::Invalid(_))
    ));
    assert!(matches!(
        store.set_status("run-1", AirunStatus::Running, t(5)).await,
        Err(AppError::Invalid(_))
    ));
    std::fs::remove_file(&path).ok();
}

#[tokio::test]
async fn outbox_delivery_is_at_least_once_and_keeps_unknown_outcomes_visible() {
    let path = journal_path();
    let store = JournalRuntimeStateStore::open(path.clone()).unwrap();
    let first = report("run-1", 1, "10000000-0000-4000-8000-0000000000a1", t(1));
    let entry = OutboxEntry::enqueue(first.clone(), t(1));
    store.enqueue(&entry).await.unwrap();
    // A failed attempt is Unknown —never a silent success, never a dropped report.
    let failed = entry.record_attempt(false, t(2)).unwrap();
    assert_eq!(failed.state, DeliveryState::Unknown);
    assert_eq!(failed.attempts, 1);
    assert!(failed.may_retry());
    store
        .update_delivery(&first.request_id, &failed)
        .await
        .unwrap();
    // The retry may deliver the same report twice; exactly-once is not claimed anywhere.
    let delivered = failed.record_attempt(true, t(3)).unwrap();
    assert_eq!(delivered.state, DeliveryState::Delivered);
    assert!(!delivered.may_retry());
    store
        .update_delivery(&first.request_id, &delivered)
        .await
        .unwrap();
    let entries = store.outbox().await.unwrap();
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].state, DeliveryState::Delivered);
    // The attempt budget is bounded: after it is spent the entry stays Unknown and visible.
    let second = report("run-1", 1, "10000000-0000-4000-8000-0000000000a2", t(4));
    let mut entry = OutboxEntry::enqueue(second.clone(), t(4));
    store.enqueue(&entry).await.unwrap();
    for _ in 0..OutboxEntry::MAX_ATTEMPTS {
        entry = entry.record_attempt(false, t(5)).unwrap();
        store
            .update_delivery(&second.request_id, &entry)
            .await
            .unwrap();
    }
    assert!(entry.record_attempt(true, t(6)).is_none());
    assert_eq!(entry.state, DeliveryState::Unknown);
    assert!(!entry.may_retry());
    std::fs::remove_file(&path).ok();
}

#[tokio::test]
async fn the_attestation_carries_counters_only_and_the_platform_wire_vocabulary() {
    let mut steps = Vec::new();
    steps.push(step(
        "10000000-0000-4000-8000-000000000083",
        1,
        StepKind::ContextBuild,
        StepStatus::Succeeded,
        t(1),
    ));
    steps.push(step(
        "10000000-0000-4000-8000-000000000083",
        1,
        StepKind::ModelCall,
        StepStatus::Succeeded,
        t(2),
    ));
    let state = RuntimeState::assemble(
        "10000000-0000-4000-8000-000000000083",
        "tenant-demo",
        1,
        AirunStatus::Running,
        steps,
        vec![checkpoint(
            "10000000-0000-4000-8000-000000000083",
            1,
            1,
            t(2),
        )],
        vec![recheck("10000000-0000-4000-8000-000000000083", 1, t(3))],
        t(3),
    )
    .unwrap();
    let attestation = state
        .report("10000000-0000-4000-8000-0000000000a3", t(4))
        .unwrap();
    assert_eq!(attestation.step_count, 2);
    assert_eq!(attestation.checkpoint_seq, 1);
    assert_eq!(attestation.evidence_recheck_count, 1);
    let wire = serde_json::to_value(&attestation).unwrap();
    // Counters and status only: no internal steps, checkpoints or evidence bodies on the wire.
    assert_eq!(wire["status"], "RUNNING");
    assert_eq!(wire["stepCount"], 2);
    assert_eq!(wire["checkpointSeq"], 1);
    assert_eq!(wire["evidenceRecheckCount"], 1);
    assert!(wire.get("steps").is_none());
    assert!(wire.get("checkpoints").is_none());
    assert!(wire.get("rechecks").is_none());
    let keys: Vec<String> = wire
        .as_object()
        .unwrap()
        .keys()
        .map(|k| k.as_str().to_owned())
        .collect();
    let mut expected = vec![
        "requestId",
        "runId",
        "attempt",
        "status",
        "stepCount",
        "checkpointSeq",
        "evidenceRecheckCount",
        "reportedAt",
    ]
    .into_iter()
    .map(str::to_owned)
    .collect::<Vec<String>>();
    let mut sorted_keys = keys.clone();
    sorted_keys.sort();
    expected.sort();
    assert_eq!(sorted_keys, expected);
}

#[test]
fn the_state_vocabulary_rejects_records_that_do_not_close() {
    assert!(StepRun::new(
        "",
        1,
        StepKind::ModelCall,
        StepStatus::Succeeded,
        t(0),
        Some(t(1)),
        None
    )
    .is_none());
    assert!(StepRun::new(
        "run-1",
        1,
        StepKind::ModelCall,
        StepStatus::Running,
        t(0),
        Some(t(1)),
        None
    )
    .is_none());
    assert!(StepRun::new(
        "run-1",
        1,
        StepKind::ModelCall,
        StepStatus::Succeeded,
        t(0),
        None,
        None
    )
    .is_none());
    assert!(StepRun::new(
        "run-1",
        1,
        StepKind::ModelCall,
        StepStatus::Failed,
        t(0),
        Some(t(1)),
        None
    )
    .is_none());
    assert!(StepRun::new(
        "run-1",
        1,
        StepKind::ModelCall,
        StepStatus::Succeeded,
        t(1),
        Some(t(0)),
        None
    )
    .is_none());
    assert!(Checkpoint::new(
        "run-1",
        1,
        0,
        StepKind::ContextBuild,
        t(0),
        vec![],
        vec![],
        t(1)
    )
    .is_none());
    assert!(Checkpoint::new(
        "run-1",
        1,
        1,
        StepKind::ContextBuild,
        t(1),
        vec![],
        vec![],
        t(0)
    )
    .is_none());
    assert!(EvidenceRecheck::new("run-1", 1, "e", t(0), t(2), t(1), t(3), t(0)).is_none());
    // A checkpoint sequence that is not monotonic is a rejected state, never a repaired one.
    assert!(RuntimeState::assemble(
        "run-1",
        "tenant-demo",
        1,
        AirunStatus::Running,
        vec![],
        vec![
            checkpoint("run-1", 1, 2, t(1)),
            checkpoint("run-1", 1, 1, t(2))
        ],
        vec![],
        t(2),
    )
    .is_none());
    // Records of another run or a future attempt never join this run's state.
    assert!(RuntimeState::assemble(
        "run-1",
        "tenant-demo",
        1,
        AirunStatus::Running,
        vec![step(
            "run-2",
            1,
            StepKind::InsightSave,
            StepStatus::Succeeded,
            t(1)
        )],
        vec![],
        vec![],
        t(2),
    )
    .is_none());
    assert!(RuntimeState::assemble(
        "run-1",
        "tenant-demo",
        1,
        AirunStatus::Running,
        vec![],
        vec![],
        vec![recheck("run-1", 2, t(1))],
        t(2),
    )
    .is_none());
}

#[test]
fn reconciliation_discloses_every_divergence_and_repairs_nothing() {
    let run = "10000000-0000-4000-8000-000000000083";
    // Consistent: no findings, and the disposition is still the constant "none".
    let consistent = reconcile(
        run,
        Some(&platform_view(run, AirunStatus::Running, 1, t(2))),
        Some(&runtime_view(AirunStatus::Running, 1, t(2))),
        0,
        t(3),
    )
    .unwrap();
    assert!(consistent.consistent());
    assert_eq!(consistent.repair(), "none");
    assert!(codes(&consistent).is_empty());

    // Platform terminal while the Runtime is still active: its own finding, not a generic mismatch.
    let diverging = reconcile(
        run,
        Some(&platform_view(run, AirunStatus::Succeeded, 1, t(5))),
        Some(&runtime_view(AirunStatus::Running, 1, t(2))),
        0,
        t(6),
    )
    .unwrap();
    assert_eq!(
        codes(&diverging),
        vec!["PLATFORM_TERMINAL_RUNTIME_ACTIVE", "STALE_RUNTIME_REPORT"]
    );

    // The reverse direction is disclosed too.
    let reverse = reconcile(
        run,
        Some(&platform_view(run, AirunStatus::Running, 1, t(2))),
        Some(&runtime_view(AirunStatus::Failed, 1, t(3))),
        0,
        t(4),
    )
    .unwrap();
    assert_eq!(codes(&reverse), vec!["RUNTIME_TERMINAL_PLATFORM_ACTIVE"]);

    // Two active sides disagreeing about status, attempt and recency.
    let mismatch = reconcile(
        run,
        Some(&platform_view(run, AirunStatus::WaitingTool, 2, t(5))),
        Some(&runtime_view(AirunStatus::Running, 1, t(2))),
        0,
        t(6),
    )
    .unwrap();
    assert_eq!(
        codes(&mismatch),
        vec![
            "STATUS_MISMATCH",
            "ATTEMPT_MISMATCH",
            "STALE_RUNTIME_REPORT"
        ]
    );

    // One side missing is explicit —the other side is never treated as the truth.
    let missing_runtime = reconcile(
        run,
        Some(&platform_view(run, AirunStatus::Running, 1, t(2))),
        None,
        0,
        t(3),
    )
    .unwrap();
    assert_eq!(codes(&missing_runtime), vec!["MISSING_RUNTIME_STATE"]);
    let missing_platform = reconcile(
        run,
        None,
        Some(&runtime_view(AirunStatus::Running, 1, t(2))),
        0,
        t(3),
    )
    .unwrap();
    assert_eq!(codes(&missing_platform), vec!["MISSING_ON_PLATFORM"]);

    // Unconfirmed deliveries are part of the divergence, not hidden inside an outage.
    let undelivered = reconcile(
        run,
        Some(&platform_view(run, AirunStatus::Running, 1, t(2))),
        Some(&runtime_view(AirunStatus::Running, 1, t(2))),
        2,
        t(3),
    )
    .unwrap();
    assert_eq!(codes(&undelivered), vec!["UNDELIVERED_STATE_REPORT"]);

    // Negative: a report of another run is rejected instead of being compared blindly.
    assert!(reconcile(
        "run-2",
        Some(&platform_view(run, AirunStatus::Running, 1, t(2))),
        None,
        0,
        t(3)
    )
    .is_none());

    // Disclose-only: the comparison mutated neither side.
    let platform = platform_view(run, AirunStatus::WaitingTool, 2, t(5));
    let runtime = runtime_view(AirunStatus::Running, 1, t(2));
    let platform_before = platform.clone();
    let runtime_before = runtime.clone();
    let before = reconcile(run, Some(&platform), Some(&runtime), 1, t(6)).unwrap();
    assert_eq!(platform, platform_before);
    assert_eq!(runtime, runtime_before);
    assert_eq!(before.repair(), "none");
    assert!(!before.consistent());
}

#[tokio::test]
async fn the_runtime_state_journal_is_wired_and_recovered_across_app_restarts() {
    use opsweave_agent_runtime::{
        api::AppState,
        config::{Config, Mode},
    };
    let dir = std::env::temp_dir().join(format!("opsweave-runtime-state-dir-{}", Uuid::new_v4()));
    let config = || Config {
        mode: Mode::Demo,
        listen: "127.0.0.1:8090".parse().unwrap(),
        dev_token: Some("a".repeat(48)),
        skill_dir: PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../extensions/skills/incident-diagnosis"),
        provider: "mock".into(),
        model: None,
        max_concurrency: 2,
        platform_url: None,
        runtime_key: None,
        state_dir: Some(dir.clone()),
    };
    let first = AppState::new(config()).unwrap();
    let store = first.runtime_state.clone().unwrap();
    store.open_run("run-1", "tenant-demo", t(0)).await.unwrap();
    store
        .record_step(&step(
            "run-1",
            1,
            StepKind::InsightSave,
            StepStatus::Succeeded,
            t(1),
        ))
        .await
        .unwrap();
    drop(first);
    // A process restart re-opens the same journal and recovers the recorded progress.
    let second = AppState::new(config()).unwrap();
    let store = second.runtime_state.clone().unwrap();
    assert!(store.recovery().records_replayed >= 2);
    assert_eq!(store.recovery().torn_tail_lines, 0);
    assert_eq!(store.state("run-1").await.unwrap().unwrap().steps.len(), 1);
    drop(second);
    std::fs::remove_dir_all(&dir).ok();
}

#[tokio::test]
async fn the_recorder_captures_the_chain_progress_through_the_port() {
    use opsweave_agent_runtime::{
        adapters::journal::RuntimeStateRecorder,
        domain::{Evidence, PlatformSnapshot},
        ports::{CurrentReadSessionPort, ModelPort},
    };
    use serde_json::json;
    use std::{collections::BTreeSet, sync::Arc};

    struct Read;
    fn evidence(id: &str) -> Evidence {
        Evidence {
            id: id.into(),
            tenant_id: "tenant-demo".into(),
            incident_id: "10000000-0000-4000-8000-000000000084".into(),
            kind: "incident".into(),
            summary: "fixture".into(),
            observed_at: t(0),
            available_at: t(0),
            expires_at: t(600),
            source_ref: "fixture".into(),
            trust: "untrusted_data".into(),
        }
    }
    fn snapshot(id: &str) -> PlatformSnapshot {
        PlatformSnapshot::new(json!({"knowledgeMode":"current"}), evidence(id))
    }
    #[async_trait::async_trait]
    impl CurrentReadSessionPort for Read {
        fn principal(&self) -> opsweave_agent_runtime::domain::Principal {
            opsweave_agent_runtime::domain::Principal {
                tenant_id: "tenant-demo".into(),
                user_id: "tester".into(),
                permissions: BTreeSet::new(),
                allowed_incidents: BTreeSet::new(),
            }
        }
        fn session_id(&self) -> &str {
            "session-1"
        }
        fn window(&self) -> &opsweave_agent_runtime::domain::TimeRange {
            use std::sync::OnceLock;
            static WINDOW: OnceLock<opsweave_agent_runtime::domain::TimeRange> = OnceLock::new();
            WINDOW.get_or_init(|| opsweave_agent_runtime::domain::TimeRange {
                from: t(0),
                to: t(60),
            })
        }
        async fn incident(&self) -> Result<PlatformSnapshot, AppError> {
            Ok(snapshot("evidence-1"))
        }
        async fn metric(&self) -> Result<PlatformSnapshot, AppError> {
            Ok(snapshot("evidence-2"))
        }
        async fn recheck(&self, _snapshot: &PlatformSnapshot) -> Result<(), AppError> {
            Ok(())
        }
        async fn save(
            &self,
            _submission: &opsweave_agent_runtime::domain::InsightSubmission,
        ) -> Result<opsweave_agent_runtime::domain::SavedInsight, AppError> {
            Ok(opsweave_agent_runtime::domain::SavedInsight {
                storage: "fixture".into(),
                record: json!({"fixture": true}),
            })
        }
    }
    struct Model;
    #[async_trait::async_trait]
    impl ModelPort for Model {
        fn name(&self) -> &str {
            "mock-deterministic"
        }
        async fn generate(
            &self,
            _system_prompt: &str,
            _question: &str,
            _context: &opsweave_agent_runtime::domain::ContextPack,
        ) -> Result<String, AppError> {
            Err(AppError::Disabled)
        }
    }
    let path = journal_path();
    let store = Arc::new(JournalRuntimeStateStore::open(path.clone()).unwrap());
    let recorder = RuntimeStateRecorder::start(
        store.clone(),
        Arc::new(Read),
        "10000000-0000-4000-8000-000000000083",
    )
    .await
    .unwrap();
    assert_eq!(recorder.attempt(), 1);
    recorder.incident().await.unwrap();
    recorder.metric().await.unwrap();
    recorder.recheck(&snapshot("evidence-1")).await.unwrap();
    recorder.mark_status(AirunStatus::Succeeded).await;
    let state = store
        .state("10000000-0000-4000-8000-000000000083")
        .await
        .unwrap()
        .unwrap();
    assert_eq!(state.status, AirunStatus::Succeeded);
    assert_eq!(state.steps.len(), 3);
    assert_eq!(state.checkpoints.len(), 2);
    assert_eq!(state.rechecks.len(), 1);
    let attestation = recorder
        .report("10000000-0000-4000-8000-0000000000a4")
        .await
        .unwrap()
        .unwrap();
    assert_eq!(attestation.status, AirunStatus::Succeeded);
    assert_eq!(attestation.step_count, 3);
    assert_eq!(attestation.checkpoint_seq, 2);
    std::fs::remove_file(&path).ok();
}
