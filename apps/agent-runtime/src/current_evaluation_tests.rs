//! M4 annotated fixture evaluation through the actual current workflow. No HTTP, PG or model provider.
//! These are boundary checks and deterministic review material, never causal-quality/human sign-off.
use crate::{
    adapters::mock_model::MockModel,
    current_workflows::diagnose,
    domain::*,
    error::AppError,
    model_spend::{ModelInput, ModelOutput, ModelPermit, ModelUsage},
    ports::{CurrentReadSessionPort, ModelPort},
    skills::LoadedSkill,
};
use async_trait::async_trait;
use chrono::{DateTime, Duration, SecondsFormat, Utc};
use serde_json::{json, Value};
use std::{
    collections::BTreeSet,
    path::PathBuf,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc, Mutex,
    },
};

const MODEL: &str = "mock-evaluation-v1";
const CORPUS: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../contracts/evals/current-diagnosis.json"
));

struct FixtureRead {
    context: CurrentContext,
    authorized: bool,
    revoked: bool,
    reads: AtomicUsize,
    rechecks: AtomicUsize,
    reservations: AtomicUsize,
    reports: AtomicUsize,
    saves: AtomicUsize,
    submitted: Mutex<Option<InsightSubmission>>,
}
impl FixtureRead {
    fn snapshot(&self, kind: &str) -> PlatformSnapshot {
        self.reads.fetch_add(1, Ordering::SeqCst);
        let document = self
            .context
            .evidence
            .iter()
            .find(|d| d["evidence"]["kind"] == kind)
            .unwrap()
            .clone();
        let evidence = serde_json::from_value(document["evidence"].clone()).unwrap();
        PlatformSnapshot { document, evidence }
    }
}
#[async_trait]
impl CurrentReadSessionPort for FixtureRead {
    fn principal(&self) -> Principal {
        Principal {
            tenant_id: self.context.tenant_id.clone(),
            user_id: "fixture-evaluator".into(),
            permissions: [
                "ai.diagnose",
                "ai.insight.read",
                "incident.read",
                "entity.read",
                "metric.read",
                "evidence.read",
            ]
            .into_iter()
            .map(String::from)
            .collect(),
            allowed_incidents: if self.authorized {
                BTreeSet::from([self.context.incident_id.clone()])
            } else {
                BTreeSet::new()
            },
        }
    }
    fn session_id(&self) -> &str {
        &self.context.session_id
    }
    fn window(&self) -> &TimeRange {
        &self.context.time_range
    }
    async fn incident(&self) -> Result<PlatformSnapshot, AppError> {
        Ok(self.snapshot("incident"))
    }
    async fn metric(&self) -> Result<PlatformSnapshot, AppError> {
        Ok(self.snapshot("metric"))
    }
    async fn reserve_model(&self, run: &str, input: &ModelInput) -> Result<ModelPermit, AppError> {
        self.reservations.fetch_add(1, Ordering::SeqCst);
        Ok(ModelPermit {
            run_id: run.into(),
            session_id: self.session_id().into(),
            provider: "mock-deterministic".into(),
            model: MODEL.into(),
            input_digest: input.digest.clone(),
            input_bytes: input.bytes,
            deadline_at: Utc::now() + Duration::seconds(50),
            record: json!({"dataMode":"labeled-fixture"}),
        })
    }
    async fn report_model(&self, _: &ModelPermit, usage: &ModelUsage) -> Result<(), AppError> {
        assert_eq!(*usage, ModelUsage::mock());
        self.reports.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
    async fn recheck(&self, snapshot: &PlatformSnapshot) -> Result<(), AppError> {
        self.rechecks.fetch_add(1, Ordering::SeqCst);
        assert!(self
            .context
            .evidence
            .iter()
            .any(|d| d["evidence"]["id"] == snapshot.evidence.id));
        if self.revoked {
            Err(AppError::Forbidden)
        } else {
            Ok(())
        }
    }
    async fn save(&self, input: &InsightSubmission) -> Result<SavedInsight, AppError> {
        self.saves.fetch_add(1, Ordering::SeqCst);
        *self.submitted.lock().unwrap() = Some(input.clone());
        Ok(SavedInsight {
            storage: "memory".into(),
            record: serde_json::to_value(input).unwrap(),
        })
    }
}

struct FixtureModel {
    baseline: bool,
    scripted: Value,
    prompt: String,
    question: String,
    calls: AtomicUsize,
    seen: Mutex<Option<CurrentContext>>,
}
#[async_trait]
impl ModelPort for FixtureModel {
    fn name(&self) -> &str {
        "mock-deterministic"
    }
    async fn generate(&self, _: &str, _: &str, _: &ContextPack) -> Result<String, AppError> {
        Err(AppError::Disabled)
    }
    async fn generate_metered(
        &self,
        input: &ModelInput,
        context: &CurrentContext,
    ) -> Result<ModelOutput, AppError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        assert_eq!(
            input.system, self.prompt,
            "Source data must not replace the pinned system prompt"
        );
        let payload: Value = serde_json::from_str(&input.payload).unwrap();
        assert_eq!(payload["untrustedUserQuestion"], self.question);
        assert_eq!(
            payload["untrustedEvidenceContext"],
            serde_json::to_value(context).unwrap()
        );
        assert_eq!(payload.as_object().unwrap().len(), 3);
        *self.seen.lock().unwrap() = Some(context.clone());
        if self.baseline {
            return MockModel.generate_metered(input, context).await;
        }
        if self.scripted["behavior"] == "timeout" {
            // Paused Tokio time exercises the production 20-second deadline without wall-clock sleep.
            tokio::time::sleep(std::time::Duration::from_secs(30)).await;
        }
        Ok(ModelOutput {
            text: self.scripted["output"].as_str().unwrap().into(),
            usage: ModelUsage::mock(),
        })
    }
}

fn rebase(value: &mut Value, delta: Duration) {
    match value {
        Value::Object(map) => {
            for child in map.values_mut() {
                rebase(child, delta);
            }
        }
        Value::Array(items) => {
            for child in items {
                rebase(child, delta);
            }
        }
        Value::String(text) => {
            if let Ok(time) = DateTime::parse_from_rfc3339(text) {
                *text =
                    (time.with_timezone(&Utc) + delta).to_rfc3339_opts(SecondsFormat::Secs, true);
            }
        }
        _ => {}
    }
}
fn error_name(error: &AppError) -> &'static str {
    match error {
        AppError::Forbidden => "forbidden",
        AppError::Platform => "platform-unavailable",
        AppError::Timeout => "timeout",
        AppError::InvalidOutput => "invalid-output",
        other => panic!("Unexpected evaluation error: {other:?}"),
    }
}
async fn run(
    case: &Value,
    skill: &LoadedSkill,
    reference: DateTime<Utc>,
    anchor: DateTime<Utc>,
    baseline: bool,
) -> Value {
    let mut context = case["context"].clone();
    rebase(&mut context, anchor - reference);
    let context: CurrentContext = serde_json::from_value(context).unwrap();
    let request = CurrentDiagnoseRequest {
        run_id: context.run_id.clone(),
        incident_id: context.incident_id.clone(),
        question: case["question"].as_str().unwrap().into(),
        time_range: context.time_range.clone(),
        knowledge_mode: "current".into(),
    };
    let read = Arc::new(FixtureRead {
        context,
        authorized: case["resourceAuthorized"].as_bool().unwrap(),
        revoked: case["recheck"] == "forbidden",
        reads: AtomicUsize::new(0),
        rechecks: AtomicUsize::new(0),
        reservations: AtomicUsize::new(0),
        reports: AtomicUsize::new(0),
        saves: AtomicUsize::new(0),
        submitted: Mutex::new(None),
    });
    let model = Arc::new(FixtureModel {
        baseline,
        scripted: case["scriptedModel"].clone(),
        prompt: skill.prompt.clone(),
        question: request.question.clone(),
        calls: AtomicUsize::new(0),
        seen: Mutex::new(None),
    });
    let result = diagnose(&request, skill, read.clone(), model.clone(), MODEL).await;
    let outcome = match &result {
        Ok(_) => "saved",
        Err(error) => error_name(error),
    };
    let expected = &case["expected"];
    assert_eq!(
        outcome,
        expected[if baseline {
            "baselineOutcome"
        } else {
            "outcome"
        }],
        "{} baseline={baseline}",
        case["id"]
    );
    let calls = model.calls.load(Ordering::SeqCst);
    let reservations = read.reservations.load(Ordering::SeqCst);
    let reports = read.reports.load(Ordering::SeqCst);
    let saves = read.saves.load(Ordering::SeqCst);
    let reads = read.reads.load(Ordering::SeqCst);
    let rechecks = read.rechecks.load(Ordering::SeqCst);
    assert_eq!(calls, reservations);
    assert!(
        calls <= 1 && reads + rechecks <= 4,
        "No retries, extra tools or fallback"
    );
    assert_eq!(reads, if read.authorized { 2 } else { 0 });
    if !baseline {
        assert_eq!(calls as u64, expected["modelCalls"].as_u64().unwrap());
        assert_eq!(reports as u64, expected["usageReports"].as_u64().unwrap());
        assert_eq!(saves as u64, expected["saveAttempts"].as_u64().unwrap());
        assert!((expected["minRechecks"].as_u64().unwrap()
            ..=expected["maxRechecks"].as_u64().unwrap())
            .contains(&(rechecks as u64)));
    }
    if let Some(seen) = model.seen.lock().unwrap().as_ref() {
        // Preserve structured facts and the malicious title as untrusted data, rather than deleting it for a green test.
        assert_eq!(seen.evidence, read.context.evidence);
    } else {
        assert_eq!(calls, 0);
    }
    let submitted = read.submitted.lock().unwrap().clone();
    if outcome == "saved" {
        let saved = submitted.as_ref().unwrap();
        assert_eq!(saves, 1);
        assert_eq!(rechecks, 2);
        assert_eq!(reports, 1);
        assert_eq!(saved.skill.digest, skill.digest);
        assert_eq!(saved.evidence_ids.len(), 2);
        for gap in expected["protectedMissing"].as_array().unwrap() {
            assert!(saved
                .insight
                .missing_data
                .contains(&gap.as_str().unwrap().to_owned()));
        }
        assert!(saved
            .insight
            .limitations
            .iter()
            .any(|s| s.contains("do not establish factual truth, support or causality")));
    } else {
        assert!(submitted.is_none());
        assert_eq!(saves, 0);
    }
    json!({"implementation":if baseline {"existing-deterministic-mock"} else {"scripted-protocol-fixture"},
        "outcome":outcome, "savedResultStorage":result.as_ref().ok().map(|saved|saved.storage.as_str()),
        "modelStepInvocations":calls, "actualProviderCalls":0,
        "reservations":reservations, "usageReports":reports, "initialReads":reads, "rechecks":rechecks, "saveAttempts":saves,
        "rebasedFixtureContext":read.context, "savedFixtureSubmission":submitted})
}

#[tokio::test(start_paused = true)]
async fn annotated_current_corpus_runs_real_workflow_with_fixture_ports() {
    let corpus: Value = serde_json::from_str(CORPUS).unwrap();
    assert_eq!(corpus["dataMode"], "labeled-fixture");
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let skill =
        LoadedSkill::load(&root.join("extensions/skills/incident-diagnosis-current")).unwrap();
    assert_eq!(skill.digest, corpus["skillRef"]["digest"]);
    assert_eq!(skill.spec.side_effects, "forbidden");
    let reference: DateTime<Utc> = corpus["referenceClock"].as_str().unwrap().parse().unwrap();
    let mut results = Vec::new();
    for case in corpus["cases"].as_array().unwrap() {
        let anchor = DateTime::from_timestamp(Utc::now().timestamp() - 2, 0).unwrap();
        let baseline = run(case, &skill, reference, anchor, true).await;
        let scripted = run(case, &skill, reference, anchor, false).await;
        assert_eq!(
            baseline["rebasedFixtureContext"],
            scripted["rebasedFixtureContext"]
        );
        results.push(
            json!({"id":case["id"], "category":case["category"], "annotation":case["annotation"],
            "deterministicBaseline":baseline, "scriptedBoundaryCheck":scripted,
            "realModelComparison":"not-run", "humanReview":"pending"}),
        );
    }
    assert_eq!(results.len(), 10);
    // Optional explicit test artifact; only the versioned synthetic corpus can enter this report.
    if let Some(destination) = std::env::var_os("OPSWEAVE_EVAL_REPORT") {
        let path = PathBuf::from(destination);
        assert!(
            path.is_absolute(),
            "Evaluation artifact path must be absolute"
        );
        let report = json!({"schemaVersion":"1.0", "dataMode":"labeled-fixture", "createdAt":Utc::now(),
            "verification":"workflow-boundaries-and-reference-integrity-only", "allMachineChecksPassed":true,
            "actualProviderCalls":0, "realModelComparison":"not-run", "humanReview":"pending", "rootCauseAccuracy":null,
            "clockPolicy":"Synthetic timestamps rebased together around current access time; relative gaps and expiry preserved.",
            "corpusDigest":crate::adapters::digest::sha256_parts(&[CORPUS.as_bytes()]),
            "skillRef":corpus["skillRef"], "cases":results});
        let bytes = serde_json::to_vec_pretty(&report).unwrap();
        assert!(bytes.len() <= 1_048_576);
        std::fs::write(path, bytes).unwrap();
    }
}
