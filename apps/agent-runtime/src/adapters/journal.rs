//! Durable Runtime state storage (M5 5d): a file journal plus an in-memory twin, and the recording
//! seam that captures the diagnosis chain's internal progress through the [`RuntimeStateStore`] port.
//!
//! The journal is append-only: one JSON record per line, flushed and fsynced per write, replayed on
//! open so a crashed process recovers every recorded step, checkpoint, evidence recheck and outbox
//! entry. A torn trailing line (a crash mid-write) is **disclosed** through [`Recovery`]: it is
//! counted and reported, never silently treated as "nothing happened"; a corrupt line anywhere else
//! fails the open instead of losing data quietly. No SQLx, no Rig, no Axum types appear here —this
//! is plain infrastructure over the domain vocabulary.
use crate::{
    domain::*,
    error::AppError,
    ports::{CurrentReadSessionPort, RuntimeStateStore},
    runtime_state::*,
};
use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    fs::{File, OpenOptions},
    io::{BufRead, BufReader, Write},
    path::PathBuf,
    sync::{atomic::AtomicU64, atomic::Ordering, Arc, Mutex},
};

/// One durable journal record. Every state change of the Runtime is one of these facts.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Record {
    Open {
        run_id: String,
        tenant_id: String,
        attempt: u32,
        at: DateTime<Utc>,
    },
    Step(StepRun),
    Checkpoint(Checkpoint),
    Recheck(EvidenceRecheck),
    Status {
        run_id: String,
        status: AirunStatus,
        at: DateTime<Utc>,
    },
    Outbox(OutboxEntry),
    Delivery {
        request_id: String,
        entry: OutboxEntry,
    },
}

/// What a restart recovered: how many records were replayed and how many torn trailing lines were
/// skipped. The second number is never hidden —an incomplete last write is a fact about the crash.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Recovery {
    pub records_replayed: u64,
    pub torn_tail_lines: u64,
}

#[derive(Default)]
struct Index {
    runs: BTreeMap<String, RuntimeState>,
    outbox: BTreeMap<String, OutboxEntry>,
}

impl Index {
    fn apply(&mut self, record: &Record) -> Result<(), AppError> {
        match record {
            Record::Open {
                run_id,
                tenant_id,
                attempt,
                at,
            } => {
                if *attempt > 1_000_000 {
                    return Err(AppError::Invalid("journal attempt out of bounds".into()));
                }
                let state = self.runs.get_mut(run_id);
                match state {
                    Some(existing) => {
                        if existing.attempt + 1 != *attempt || existing.tenant_id != *tenant_id {
                            return Err(AppError::Invalid(
                                "journal attempt sequence mismatch".into(),
                            ));
                        }
                        existing.attempt = *attempt;
                        existing.status = AirunStatus::Running;
                        existing.updated_at = *at;
                    }
                    None => {
                        let state = RuntimeState::assemble(
                            run_id,
                            tenant_id,
                            *attempt,
                            AirunStatus::Running,
                            Vec::new(),
                            Vec::new(),
                            Vec::new(),
                            *at,
                        )
                        .ok_or_else(|| AppError::Invalid("journal open record rejected".into()))?;
                        self.runs.insert(run_id.clone(), state);
                    }
                }
                Ok(())
            }
            Record::Step(step) => {
                let state = self
                    .runs
                    .get_mut(&step.run_id)
                    .ok_or_else(|| AppError::Invalid("step record without an open run".into()))?;
                state.steps.push(step.clone());
                state.updated_at = step.finished_at.unwrap_or(step.started_at);
                Ok(())
            }
            Record::Checkpoint(checkpoint) => {
                let state = self
                    .runs
                    .get_mut(&checkpoint.run_id)
                    .ok_or_else(|| AppError::Invalid("checkpoint without an open run".into()))?;
                if state
                    .checkpoints
                    .last()
                    .is_some_and(|last| last.seq >= checkpoint.seq)
                {
                    return Err(AppError::Invalid(
                        "checkpoint sequence is not monotonic".into(),
                    ));
                }
                state.checkpoints.push(checkpoint.clone());
                state.updated_at = checkpoint.saved_at;
                Ok(())
            }
            Record::Recheck(recheck) => {
                let state = self.runs.get_mut(&recheck.run_id).ok_or_else(|| {
                    AppError::Invalid("recheck record without an open run".into())
                })?;
                state.rechecks.push(recheck.clone());
                state.updated_at = recheck.recorded_at;
                Ok(())
            }
            Record::Status { run_id, status, at } => {
                let state = self
                    .runs
                    .get_mut(run_id)
                    .ok_or_else(|| AppError::Invalid("status record without an open run".into()))?;
                if state.status.terminal() || !state.status.can_transition_to(status) {
                    return Err(AppError::Invalid(
                        "illegal internal status transition".into(),
                    ));
                }
                state.status = status.clone();
                state.updated_at = *at;
                Ok(())
            }
            Record::Outbox(entry) => {
                self.outbox
                    .insert(entry.report.request_id.clone(), entry.clone());
                Ok(())
            }
            Record::Delivery { request_id, entry } => {
                if !self.outbox.contains_key(request_id) {
                    return Err(AppError::Invalid(
                        "delivery record without an outbox entry".into(),
                    ));
                }
                self.outbox.insert(request_id.clone(), entry.clone());
                Ok(())
            }
        }
    }
}

fn record_step(index: &mut Index, step: &StepRun) -> Result<Record, AppError> {
    let record = Record::Step(step.clone());
    index.apply(&record)?;
    Ok(record)
}

/// In-memory twin of the journal store: same semantics, no durability. Explicitly not a stand-in for
/// durability —it never claims to survive a crash.
#[derive(Default)]
pub struct MemoryRuntimeStateStore {
    index: Mutex<Index>,
}

impl MemoryRuntimeStateStore {
    pub fn new() -> Self {
        Self::default()
    }
}

#[async_trait]
impl RuntimeStateStore for MemoryRuntimeStateStore {
    async fn record_step(&self, step: &StepRun) -> Result<(), AppError> {
        let mut index = self.index.lock().map_err(|_| AppError::Platform)?;
        record_step(&mut index, step).map(|_| ())
    }
    async fn save_checkpoint(&self, checkpoint: &Checkpoint) -> Result<(), AppError> {
        let mut index = self.index.lock().map_err(|_| AppError::Platform)?;
        index.apply(&Record::Checkpoint(checkpoint.clone()))
    }
    async fn record_recheck(&self, recheck: &EvidenceRecheck) -> Result<(), AppError> {
        let mut index = self.index.lock().map_err(|_| AppError::Platform)?;
        index.apply(&Record::Recheck(recheck.clone()))
    }
    async fn open_run(
        &self,
        run_id: &str,
        tenant_id: &str,
        at: DateTime<Utc>,
    ) -> Result<RuntimeState, AppError> {
        let mut index = self.index.lock().map_err(|_| AppError::Platform)?;
        let attempt = index.runs.get(run_id).map(|s| s.attempt).unwrap_or(0) + 1;
        index.apply(&Record::Open {
            run_id: run_id.to_owned(),
            tenant_id: tenant_id.to_owned(),
            attempt,
            at,
        })?;
        index
            .runs
            .get(run_id)
            .cloned()
            .ok_or_else(|| AppError::Platform)
    }
    async fn set_status(
        &self,
        run_id: &str,
        status: AirunStatus,
        at: DateTime<Utc>,
    ) -> Result<(), AppError> {
        let mut index = self.index.lock().map_err(|_| AppError::Platform)?;
        index.apply(&Record::Status {
            run_id: run_id.to_owned(),
            status,
            at,
        })
    }
    async fn state(&self, run_id: &str) -> Result<Option<RuntimeState>, AppError> {
        let index = self.index.lock().map_err(|_| AppError::Platform)?;
        Ok(index.runs.get(run_id).cloned())
    }
    async fn enqueue(&self, entry: &OutboxEntry) -> Result<(), AppError> {
        let mut index = self.index.lock().map_err(|_| AppError::Platform)?;
        if index.outbox.contains_key(&entry.report.request_id) {
            return Err(AppError::Invalid("outbox entry already exists".into()));
        }
        index.apply(&Record::Outbox(entry.clone()))
    }
    async fn outbox(&self) -> Result<Vec<OutboxEntry>, AppError> {
        let index = self.index.lock().map_err(|_| AppError::Platform)?;
        Ok(index.outbox.values().cloned().collect())
    }
    async fn update_delivery(&self, request_id: &str, entry: &OutboxEntry) -> Result<(), AppError> {
        let mut index = self.index.lock().map_err(|_| AppError::Platform)?;
        index.apply(&Record::Delivery {
            request_id: request_id.to_owned(),
            entry: entry.clone(),
        })
    }
}

/// The durable journal store: append-only records, fsynced per write, replayed on open.
pub struct JournalRuntimeStateStore {
    file: Mutex<File>,
    index: Mutex<Index>,
    recovery: Recovery,
}

impl JournalRuntimeStateStore {
    /// Open (or create) the journal at `path` and replay whatever is durable. A torn trailing line
    /// is counted in [`Recovery::torn_tail_lines`] and skipped —explicitly, not silently.
    pub fn open(path: PathBuf) -> Result<Self, AppError> {
        let mut index = Index::default();
        let mut recovery = Recovery {
            records_replayed: 0,
            torn_tail_lines: 0,
        };
        if path.exists() {
            let file = File::open(&path).map_err(|_| AppError::Platform)?;
            let mut lines: Vec<String> = BufReader::new(file)
                .lines()
                .collect::<Result<_, _>>()
                .map_err(|_| AppError::Platform)?;
            while let Some(line) = lines.pop() {
                if line.trim().is_empty() {
                    continue;
                }
                match serde_json::from_str::<Record>(&line) {
                    Ok(_record) => {
                        lines.push(line);
                        break;
                    }
                    Err(_) => recovery.torn_tail_lines += 1,
                }
            }
            for line in &lines {
                if line.trim().is_empty() {
                    continue;
                }
                let record: Record = serde_json::from_str(line).map_err(|_| {
                    AppError::Invalid("journal record is corrupt before its tail".into())
                })?;
                index.apply(&record)?;
                recovery.records_replayed += 1;
            }
        }
        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&path)
            .map_err(|_| AppError::Platform)?;
        Ok(Self {
            file: Mutex::new(file),
            index: Mutex::new(index),
            recovery,
        })
    }

    /// The restart recovery facts of this open; never zeroed, never hidden.
    pub fn recovery(&self) -> Recovery {
        self.recovery
    }

    fn append(&self, record: &Record) -> Result<(), AppError> {
        let mut index = self.index.lock().map_err(|_| AppError::Platform)?;
        index.apply(record)?;
        let mut line = serde_json::to_string(record).map_err(|_| AppError::Platform)?;
        line.push('\n');
        let mut file = self.file.lock().map_err(|_| AppError::Platform)?;
        file.write_all(line.as_bytes())
            .map_err(|_| AppError::Platform)?;
        file.flush().map_err(|_| AppError::Platform)?;
        file.sync_all().map_err(|_| AppError::Platform)?;
        Ok(())
    }
}

#[async_trait]
impl RuntimeStateStore for JournalRuntimeStateStore {
    async fn record_step(&self, step: &StepRun) -> Result<(), AppError> {
        self.append(&Record::Step(step.clone()))
    }
    async fn save_checkpoint(&self, checkpoint: &Checkpoint) -> Result<(), AppError> {
        self.append(&Record::Checkpoint(checkpoint.clone()))
    }
    async fn record_recheck(&self, recheck: &EvidenceRecheck) -> Result<(), AppError> {
        self.append(&Record::Recheck(recheck.clone()))
    }
    async fn open_run(
        &self,
        run_id: &str,
        tenant_id: &str,
        at: DateTime<Utc>,
    ) -> Result<RuntimeState, AppError> {
        let mut index = self.index.lock().map_err(|_| AppError::Platform)?;
        let attempt = index.runs.get(run_id).map(|s| s.attempt).unwrap_or(0) + 1;
        let record = Record::Open {
            run_id: run_id.to_owned(),
            tenant_id: tenant_id.to_owned(),
            attempt,
            at,
        };
        index.apply(&record)?;
        let state = index.runs.get(run_id).cloned().ok_or(AppError::Platform)?;
        drop(index);
        let mut line = serde_json::to_string(&record).map_err(|_| AppError::Platform)?;
        line.push('\n');
        let mut file = self.file.lock().map_err(|_| AppError::Platform)?;
        file.write_all(line.as_bytes())
            .map_err(|_| AppError::Platform)?;
        file.flush().map_err(|_| AppError::Platform)?;
        file.sync_all().map_err(|_| AppError::Platform)?;
        Ok(state)
    }
    async fn set_status(
        &self,
        run_id: &str,
        status: AirunStatus,
        at: DateTime<Utc>,
    ) -> Result<(), AppError> {
        self.append(&Record::Status {
            run_id: run_id.to_owned(),
            status,
            at,
        })
    }
    async fn state(&self, run_id: &str) -> Result<Option<RuntimeState>, AppError> {
        let index = self.index.lock().map_err(|_| AppError::Platform)?;
        Ok(index.runs.get(run_id).cloned())
    }
    async fn enqueue(&self, entry: &OutboxEntry) -> Result<(), AppError> {
        let index = self.index.lock().map_err(|_| AppError::Platform)?;
        if index.outbox.contains_key(&entry.report.request_id) {
            return Err(AppError::Invalid("outbox entry already exists".into()));
        }
        drop(index);
        self.append(&Record::Outbox(entry.clone()))
    }
    async fn outbox(&self) -> Result<Vec<OutboxEntry>, AppError> {
        let index = self.index.lock().map_err(|_| AppError::Platform)?;
        Ok(index.outbox.values().cloned().collect())
    }
    async fn update_delivery(&self, request_id: &str, entry: &OutboxEntry) -> Result<(), AppError> {
        self.append(&Record::Delivery {
            request_id: request_id.to_owned(),
            entry: entry.clone(),
        })
    }
}

/// The recording seam over one diagnosis chain: it wraps the read session and records every internal
/// progress step (context build reads, the model call, evidence rechecks, the insight save) into the
/// Runtime's own store. Recording is observability, not authority: a store failure is logged and the
/// chain continues —it never fakes a record and never fails the diagnosis on its own.
pub struct RuntimeStateRecorder {
    inner: Arc<dyn CurrentReadSessionPort>,
    store: Arc<dyn RuntimeStateStore>,
    run_id: String,
    attempt: u32,
    checkpoint_seq: AtomicU64,
}

impl RuntimeStateRecorder {
    /// Open (or continue) the run's internal state for one dispatch attempt.
    pub async fn start(
        store: Arc<dyn RuntimeStateStore>,
        inner: Arc<dyn CurrentReadSessionPort>,
        run_id: &str,
    ) -> Result<Self, AppError> {
        let principal = inner.principal();
        let state = store
            .open_run(run_id, &principal.tenant_id, Utc::now())
            .await?;
        let checkpoint_seq = state.checkpoints.last().map(|c| c.seq).unwrap_or(0);
        Ok(Self {
            inner,
            store,
            run_id: run_id.to_owned(),
            attempt: state.attempt,
            checkpoint_seq: AtomicU64::new(checkpoint_seq),
        })
    }

    pub fn attempt(&self) -> u32 {
        self.attempt
    }

    /// Move the internal status (for example the terminal landing of one attempt).
    pub async fn mark_status(&self, status: AirunStatus) {
        let recorded = self
            .store
            .set_status(&self.run_id, status, Utc::now())
            .await;
        if recorded.is_err() {
            tracing::warn!(run_id = %self.run_id, "runtime state status not recorded; the chain is unaffected");
        }
    }

    /// A closed attestation of the recorded internal state, ready for at-least-once delivery.
    pub async fn report(&self, request_id: &str) -> Result<Option<RuntimeStateReport>, AppError> {
        let state = self.store.state(&self.run_id).await?;
        Ok(state.and_then(|s| s.report(request_id, Utc::now())))
    }

    async fn step(
        &self,
        step: StepKind,
        status: StepStatus,
        started_at: DateTime<Utc>,
        finished_at: Option<DateTime<Utc>>,
        reason: Option<String>,
    ) {
        let record = StepRun::new(
            &self.run_id,
            self.attempt,
            step,
            status,
            started_at,
            finished_at,
            reason,
        );
        if let Some(step) = record {
            if self.store.record_step(&step).await.is_err() {
                tracing::warn!(run_id = %self.run_id, "runtime state step not recorded; the chain is unaffected");
            }
        }
    }

    async fn checkpoint(&self, step: StepKind, snapshot: &PlatformSnapshot) {
        let evidence_ids = vec![snapshot.evidence().id.clone()];
        let record = Checkpoint::new(
            &self.run_id,
            self.attempt,
            self.checkpoint_seq.fetch_add(1, Ordering::SeqCst) + 1,
            step,
            Utc::now(),
            evidence_ids,
            Vec::new(),
            Utc::now(),
        );
        if let Some(checkpoint) = record {
            if self.store.save_checkpoint(&checkpoint).await.is_err() {
                tracing::warn!(run_id = %self.run_id, "runtime state checkpoint not recorded; the chain is unaffected");
            }
        }
    }

    async fn evidence_recheck(&self, snapshot: &PlatformSnapshot) {
        let e = snapshot.evidence();
        let record = EvidenceRecheck::new(
            &self.run_id,
            self.attempt,
            &e.id,
            Utc::now(),
            e.observed_at,
            e.available_at,
            e.expires_at,
            Utc::now(),
        );
        if let Some(recheck) = record {
            if self.store.record_recheck(&recheck).await.is_err() {
                tracing::warn!(run_id = %self.run_id, "runtime state recheck not recorded; the chain is unaffected");
            }
        }
    }
}

#[async_trait]
impl CurrentReadSessionPort for RuntimeStateRecorder {
    fn principal(&self) -> Principal {
        self.inner.principal()
    }
    fn session_id(&self) -> &str {
        self.inner.session_id()
    }
    fn window(&self) -> &TimeRange {
        self.inner.window()
    }
    async fn reserve_model(
        &self,
        run: &str,
        input: &crate::model_spend::ModelInput,
    ) -> Result<crate::model_spend::ModelPermit, AppError> {
        self.step(
            StepKind::ModelCall,
            StepStatus::Running,
            Utc::now(),
            None,
            None,
        )
        .await;
        self.inner.reserve_model(run, input).await
    }
    async fn report_model(
        &self,
        permit: &crate::model_spend::ModelPermit,
        usage: &crate::model_spend::ModelUsage,
    ) -> Result<(), AppError> {
        let reported = self.inner.report_model(permit, usage).await;
        self.step(
            StepKind::ModelCall,
            StepStatus::Succeeded,
            Utc::now(),
            Some(Utc::now()),
            None,
        )
        .await;
        reported
    }
    async fn incident(&self) -> Result<PlatformSnapshot, AppError> {
        let snapshot = self.inner.incident().await;
        if snapshot.is_ok() {
            self.step(
                StepKind::ContextBuild,
                StepStatus::Succeeded,
                Utc::now(),
                Some(Utc::now()),
                None,
            )
            .await;
            if let Ok(value) = &snapshot {
                self.checkpoint(StepKind::ContextBuild, value).await;
            }
        }
        snapshot
    }
    async fn metric(&self) -> Result<PlatformSnapshot, AppError> {
        let snapshot = self.inner.metric().await;
        if snapshot.is_ok() {
            self.step(
                StepKind::ContextBuild,
                StepStatus::Succeeded,
                Utc::now(),
                Some(Utc::now()),
                None,
            )
            .await;
            if let Ok(value) = &snapshot {
                self.checkpoint(StepKind::ContextBuild, value).await;
            }
        }
        snapshot
    }
    async fn recheck(&self, snapshot: &PlatformSnapshot) -> Result<(), AppError> {
        let outcome = self.inner.recheck(snapshot).await;
        if outcome.is_ok() {
            self.evidence_recheck(snapshot).await;
            self.step(
                StepKind::EvidenceRecheck,
                StepStatus::Succeeded,
                Utc::now(),
                Some(Utc::now()),
                None,
            )
            .await;
        }
        outcome
    }
    async fn save(&self, submission: &InsightSubmission) -> Result<SavedInsight, AppError> {
        let saved = self.inner.save(submission).await;
        if saved.is_ok() {
            self.step(
                StepKind::InsightSave,
                StepStatus::Succeeded,
                Utc::now(),
                Some(Utc::now()),
                None,
            )
            .await;
        }
        saved
    }
}
