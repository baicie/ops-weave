use crate::{
    context,
    domain::*,
    error::AppError,
    policies::authorize,
    ports::{CurrentReadSessionPort, ModelPort},
    skills::LoadedSkill,
};
use chrono::Utc;
use std::{collections::BTreeSet, sync::Arc, time::Duration};
use uuid::Uuid;

pub fn validate_request(request: &CurrentDiagnoseRequest) -> Result<(), AppError> {
    if Uuid::parse_str(&request.run_id).is_err()
        || Uuid::parse_str(&request.incident_id).is_err()
        || request.knowledge_mode != "current"
        || request.question.trim().is_empty()
        || request.question.chars().count() > 2000
        || request.time_range.from >= request.time_range.to
        || request.time_range.to > Utc::now()
        || request.time_range.from.timestamp() < 0
        || request.time_range.to - request.time_range.from > chrono::Duration::hours(1)
        || request.time_range.from.timestamp_subsec_nanos() != 0
        || request.time_range.to.timestamp_subsec_nanos() != 0
    {
        return Err(AppError::Invalid("Require current knowledge, UUIDs, a question and a past whole-second window of at most one hour".into()));
    }
    Ok(())
}

/// One model step, two bounded native reads, two fresh rechecks and one idempotent platform save.
pub async fn diagnose(
    request: &CurrentDiagnoseRequest,
    skill: &LoadedSkill,
    read: Arc<dyn CurrentReadSessionPort>,
    model: Arc<dyn ModelPort>,
    model_name: &str,
) -> Result<SavedInsight, AppError> {
    validate_request(request)?;
    if skill.spec.execution_template != "readonly-current-incident-diagnosis-v1"
        || skill.spec.version != "2.0.0"
        || skill.spec.max_tool_calls != 4
        || skill.spec.max_evidence != 2
        || model_name.trim().is_empty()
        || model_name.chars().count() > 128
    {
        return Err(AppError::Configuration(
            "Current diagnosis requires the pinned current-knowledge Skill and model policy".into(),
        ));
    }
    let principal = read.principal();
    for permission in [
        "ai.diagnose",
        "ai.insight.read",
        "incident.read",
        "entity.read",
        "metric.read",
        "evidence.read",
    ] {
        authorize(&principal, &request.incident_id, permission)?;
    }
    if read.window().from != request.time_range.from || read.window().to != request.time_range.to {
        return Err(AppError::Forbidden);
    }
    tokio::time::timeout(
        Duration::from_millis(skill.spec.deadline_ms),
        execute(request, skill, read, model, model_name),
    )
    .await
    .map_err(|_| AppError::Timeout)?
}
async fn execute(
    request: &CurrentDiagnoseRequest,
    skill: &LoadedSkill,
    read: Arc<dyn CurrentReadSessionPort>,
    model: Arc<dyn ModelPort>,
    model_name: &str,
) -> Result<SavedInsight, AppError> {
    let (incident, metric) = tokio::try_join!(read.incident(), read.metric())?;
    let snapshots = [&incident, &metric];
    let as_of = Utc::now();
    let evidence: Vec<Evidence> = snapshots.iter().map(|s| s.evidence().clone()).collect();
    let principal = read.principal();
    let mut ids = BTreeSet::new();
    let mut missing = BTreeSet::new();
    for snapshot in &snapshots {
        let e = snapshot.evidence();
        if e.tenant_id != principal.tenant_id
            || e.incident_id != request.incident_id
            || e.trust != "untrusted_data"
            || e.available_at > as_of
            || e.observed_at > e.available_at
            || e.expires_at <= as_of
            || !ids.insert(e.id.clone())
            || snapshot.document()["knowledgeMode"] != "current"
            || snapshot.document()["sessionId"] != read.session_id()
        {
            return Err(AppError::Platform);
        }
        for warning in snapshot.document()["warnings"]
            .as_array()
            .ok_or(AppError::Platform)?
        {
            missing.insert(warning.as_str().ok_or(AppError::Platform)?.to_owned());
        }
    }
    if incident.evidence().kind != "incident"
        || metric.evidence().kind != "metric"
        || missing.len() > 32
    {
        return Err(AppError::Platform);
    }
    let context = CurrentContext {
        schema_version: "1.0".into(),
        knowledge_mode: "current".into(),
        run_id: request.run_id.clone(),
        tenant_id: principal.tenant_id,
        incident_id: request.incident_id.clone(),
        session_id: read.session_id().into(),
        time_range: request.time_range.clone(),
        as_of,
        built_at: Utc::now(),
        evidence: snapshots.iter().map(|s| s.document().clone()).collect(),
        missing_data: missing.into_iter().collect(),
    };
    if serde_json::to_vec(&context)
        .map_err(|_| AppError::InvalidOutput)?
        .len()
        + request.question.len()
        + skill.prompt.len()
        > skill.spec.max_context_bytes
    {
        return Err(AppError::Invalid("Context byte budget exceeded".into()));
    }
    let input =
        crate::model_spend::ModelInput::current(&skill.prompt, &request.question, &context)?;
    let permit = read.reserve_model(&request.run_id, &input).await?;
    permit.check(
        &request.run_id,
        read.session_id(),
        model.name(),
        model_name,
        &input,
    )?;
    let generated = tokio::time::timeout(
        Duration::from_secs(20),
        model.generate_metered(&input, &context),
    )
    .await
    .map_err(|_| AppError::Timeout)??;
    generated.usage.validate(model.name())?;
    // Record consumed tokens before parsing or rechecking evidence: an invalid answer can still be billed.
    read.report_model(&permit, &generated.usage).await?;
    let mut insight = skill.parse_output(&generated.text)?;
    let mut missing = context.missing_data.clone();
    for gap in insight.missing_data {
        if missing.len() < 32 && !missing.contains(&gap) {
            missing.push(gap);
        }
    }
    insight.missing_data = missing;
    insight.limitations.insert(0, "Current knowledge only; evidence references do not establish factual truth, support or causality.".into());
    insight.limitations.truncate(16);
    insight = skill
        .parse_output(&serde_json::to_string(&insight).map_err(|_| AppError::InvalidOutput)?)?;
    context::validate_references(&insight, &evidence, Utc::now())?;
    tokio::try_join!(read.recheck(&incident), read.recheck(&metric))?;
    context::validate_references(&insight, &evidence, Utc::now())?;
    let submitted = InsightSubmission {
        run_id: request.run_id.clone(),
        session_id: read.session_id().into(),
        question: request.question.clone(),
        as_of,
        built_at: context.built_at,
        completed_at: Utc::now(),
        skill: InsightSkillRef {
            id: skill.spec.id.clone(),
            version: skill.spec.version.clone(),
            digest: skill.digest.clone(),
        },
        model: InsightModelRef {
            provider: model.name().into(),
            name: model_name.into(),
        },
        evidence_ids: evidence.into_iter().map(|e| e.id).collect(),
        insight,
    };
    read.save(&submitted).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;
    use serde_json::json;
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Mutex,
    };
    const INCIDENT: &str = "26933d69-22f2-40fc-9e5b-d0b8e988f933";
    const SESSION: &str = "c306fb10-234e-4ca8-ae7b-74b133fb0672";
    struct Read {
        window: TimeRange,
        mode: &'static str,
        calls: AtomicUsize,
        saves: AtomicUsize,
        reports: AtomicUsize,
        submitted: Mutex<Option<InsightSubmission>>,
    }
    struct Model {
        mode: &'static str,
        calls: AtomicUsize,
        seen: Mutex<Option<CurrentContext>>,
    }
    fn request() -> CurrentDiagnoseRequest {
        let to = chrono::DateTime::from_timestamp(Utc::now().timestamp() - 10, 0).unwrap();
        CurrentDiagnoseRequest {
            run_id: Uuid::new_v4().to_string(),
            incident_id: INCIDENT.into(),
            question: "Summarize current evidence".into(),
            time_range: TimeRange {
                from: to - chrono::Duration::minutes(15),
                to,
            },
            knowledge_mode: "current".into(),
        }
    }
    fn skill() -> LoadedSkill {
        LoadedSkill::load(
            &std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../extensions/skills/incident-diagnosis-current"),
        )
        .unwrap()
    }
    fn fixtures(
        mode: &'static str,
        model_mode: &'static str,
    ) -> (CurrentDiagnoseRequest, Arc<Read>, Arc<Model>) {
        let r = request();
        let read = Arc::new(Read {
            window: r.time_range.clone(),
            mode,
            calls: AtomicUsize::new(0),
            saves: AtomicUsize::new(0),
            reports: AtomicUsize::new(0),
            submitted: Mutex::new(None),
        });
        let model = Arc::new(Model {
            mode: model_mode,
            calls: AtomicUsize::new(0),
            seen: Mutex::new(None),
        });
        (r, read, model)
    }
    impl Read {
        fn snapshot(&self, kind: &str) -> PlatformSnapshot {
            self.calls.fetch_add(1, Ordering::SeqCst);
            let now = Utc::now();
            let evidence = Evidence {
                id: Uuid::new_v4().to_string(),
                tenant_id: if self.mode == "cross-tenant" {
                    "other"
                } else {
                    "tenant-test"
                }
                .into(),
                incident_id: INCIDENT.into(),
                kind: kind.into(),
                summary: "Explicit test fixture".into(),
                observed_at: now - chrono::Duration::seconds(2),
                available_at: if self.mode == "future" {
                    now + chrono::Duration::hours(1)
                } else {
                    now - chrono::Duration::seconds(1)
                },
                expires_at: now + chrono::Duration::hours(1),
                source_ref: "fixture-test".into(),
                trust: "untrusted_data".into(),
            };
            let document = json!({"evidence":evidence,"knowledgeMode":"current","sessionId":SESSION,"warnings":["CURRENT_KNOWLEDGE_ONLY","METRIC_INGEST_TIME_UNAVAILABLE"],"data":{"fixtureMarker":"structured samples preserved"}});
            PlatformSnapshot { document, evidence }
        }
    }
    #[async_trait]
    impl CurrentReadSessionPort for Read {
        async fn reserve_model(
            &self,
            run: &str,
            input: &crate::model_spend::ModelInput,
        ) -> Result<crate::model_spend::ModelPermit, AppError> {
            if self.mode == "budget-denied" {
                return Err(AppError::Busy);
            }
            Ok(crate::model_spend::ModelPermit {
                run_id: run.into(),
                session_id: SESSION.into(),
                provider: "mock-deterministic".into(),
                model: if self.mode == "wrong-model" {
                    "other"
                } else {
                    "mock-current-v1"
                }
                .into(),
                input_digest: input.digest.clone(),
                input_bytes: input.bytes,
                deadline_at: Utc::now() + chrono::Duration::seconds(50),
                record: json!({}),
            })
        }
        async fn report_model(
            &self,
            _: &crate::model_spend::ModelPermit,
            usage: &crate::model_spend::ModelUsage,
        ) -> Result<(), AppError> {
            assert_eq!(*usage, crate::model_spend::ModelUsage::mock());
            self.reports.fetch_add(1, Ordering::SeqCst);
            if self.mode == "report-fails" {
                return Err(AppError::Platform);
            }
            Ok(())
        }
        fn principal(&self) -> Principal {
            Principal {
                tenant_id: "tenant-test".into(),
                user_id: "reader".into(),
                allowed_incidents: BTreeSet::from([INCIDENT.into()]),
                permissions: if self.mode == "denied" {
                    BTreeSet::new()
                } else {
                    [
                        "ai.diagnose",
                        "ai.insight.read",
                        "incident.read",
                        "entity.read",
                        "metric.read",
                        "evidence.read",
                    ]
                    .into_iter()
                    .map(String::from)
                    .collect()
                },
            }
        }
        fn session_id(&self) -> &str {
            SESSION
        }
        fn window(&self) -> &TimeRange {
            &self.window
        }
        async fn incident(&self) -> Result<PlatformSnapshot, AppError> {
            Ok(self.snapshot("incident"))
        }
        async fn metric(&self) -> Result<PlatformSnapshot, AppError> {
            if self.mode == "unavailable" {
                Err(AppError::Platform)
            } else {
                Ok(self.snapshot("metric"))
            }
        }
        async fn recheck(&self, _: &PlatformSnapshot) -> Result<(), AppError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            if self.mode == "revoked" {
                Err(AppError::Forbidden)
            } else {
                Ok(())
            }
        }
        async fn save(&self, input: &InsightSubmission) -> Result<SavedInsight, AppError> {
            self.saves.fetch_add(1, Ordering::SeqCst);
            if self.mode == "save-fails" {
                return Err(AppError::Platform);
            }
            *self.submitted.lock().unwrap() = Some(input.clone());
            Ok(SavedInsight {
                storage: "memory".into(),
                record: json!({"id":input.run_id}),
            })
        }
    }
    #[async_trait]
    impl ModelPort for Model {
        fn name(&self) -> &str {
            "mock-deterministic"
        }
        async fn generate(&self, _: &str, _: &str, _: &ContextPack) -> Result<String, AppError> {
            Err(AppError::Disabled)
        }
        async fn generate_current(
            &self,
            _: &str,
            _: &str,
            context: &CurrentContext,
        ) -> Result<String, AppError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            *self.seen.lock().unwrap() = Some(context.clone());
            if self.mode == "error" {
                return Err(AppError::Provider);
            }
            if self.mode == "slow" {
                tokio::time::sleep(Duration::from_secs(30)).await;
            }
            let mut result = json!({"summary":"Fixture summary","findings":[{"kind":"observation","statement":"Fixture observed","evidenceRefs":[context.evidence[0]["evidence"]["id"]]}],"missingData":[],"limitations":[]});
            if self.mode == "forged" {
                result["findings"][0]["evidenceRefs"][0] = json!(Uuid::new_v4().to_string());
            }
            if self.mode == "actions" {
                result["actions"] = json!(["restart"]);
            }
            Ok(result.to_string())
        }
    }
    #[tokio::test]
    async fn preserves_structured_current_context_and_saves_after_four_reads() {
        let (r, read, model) = fixtures("ok", "ok");
        let result = diagnose(&r, &skill(), read.clone(), model.clone(), "mock-current-v1")
            .await
            .unwrap();
        assert_eq!(result.record["id"], r.run_id);
        assert_eq!(read.calls.load(Ordering::SeqCst), 4);
        assert_eq!(read.saves.load(Ordering::SeqCst), 1);
        assert_eq!(model.calls.load(Ordering::SeqCst), 1);
        assert_eq!(read.reports.load(Ordering::SeqCst), 1);
        let context = model.seen.lock().unwrap().clone().unwrap();
        assert_eq!(
            context.evidence[0]["data"]["fixtureMarker"],
            "structured samples preserved"
        );
        assert!(context.as_of > r.time_range.to);
        assert!(context
            .missing_data
            .contains(&"METRIC_INGEST_TIME_UNAVAILABLE".into()));
        let submitted = read.submitted.lock().unwrap().clone().unwrap();
        assert_eq!(submitted.evidence_ids.len(), 2);
        assert_eq!(submitted.skill.version, "2.0.0");
        assert!(submitted
            .insight
            .missing_data
            .contains(&"CURRENT_KNOWLEDGE_ONLY".into()));
        assert!(submitted.completed_at >= context.built_at);
    }
    #[tokio::test]
    async fn invalid_or_denied_inputs_never_reach_the_model() {
        for mode in [
            "cross-tenant",
            "future",
            "denied",
            "unavailable",
            "budget-denied",
            "wrong-model",
        ] {
            let (r, read, model) = fixtures(mode, "ok");
            assert!(
                diagnose(&r, &skill(), read.clone(), model.clone(), "mock-current-v1")
                    .await
                    .is_err(),
                "{mode}"
            );
            assert_eq!(model.calls.load(Ordering::SeqCst), 0);
            assert_eq!(read.saves.load(Ordering::SeqCst), 0);
        }
    }
    #[tokio::test]
    async fn invalid_model_output_and_revocation_never_save() {
        for (mode, model_mode) in [
            ("ok", "forged"),
            ("ok", "actions"),
            ("ok", "error"),
            ("revoked", "ok"),
            ("report-fails", "ok"),
        ] {
            let (r, read, model) = fixtures(mode, model_mode);
            assert!(
                diagnose(&r, &skill(), read.clone(), model.clone(), "mock-current-v1")
                    .await
                    .is_err()
            );
            assert_eq!(model.calls.load(Ordering::SeqCst), 1);
            assert_eq!(read.saves.load(Ordering::SeqCst), 0);
            assert_eq!(
                read.reports.load(Ordering::SeqCst),
                if model_mode == "error" { 0 } else { 1 }
            );
        }
    }
    #[tokio::test]
    async fn persistence_failure_is_not_a_successful_run() {
        let (r, read, model) = fixtures("save-fails", "ok");
        assert!(matches!(
            diagnose(&r, &skill(), read.clone(), model, "mock-current-v1").await,
            Err(AppError::Platform)
        ));
        assert_eq!(read.saves.load(Ordering::SeqCst), 1);
        assert!(read.submitted.lock().unwrap().is_none());
    }
    #[tokio::test(start_paused = true)]
    async fn model_deadline_stops_before_rechecks_or_save() {
        let (r, read, model) = fixtures("ok", "slow");
        assert!(matches!(
            diagnose(&r, &skill(), read.clone(), model.clone(), "mock-current-v1").await,
            Err(AppError::Timeout)
        ));
        assert_eq!(read.calls.load(Ordering::SeqCst), 2);
        assert_eq!(read.saves.load(Ordering::SeqCst), 0);
        assert_eq!(model.calls.load(Ordering::SeqCst), 1);
        assert_eq!(read.reports.load(Ordering::SeqCst), 0);
    }
    #[test]
    fn current_request_cannot_accept_a_historical_cutoff_or_identity() {
        let r = request();
        let mut body = serde_json::to_value(&r).unwrap();
        body["asOf"] = json!("2020-01-01T00:00:00Z");
        assert!(serde_json::from_value::<CurrentDiagnoseRequest>(body).is_err());
        let mut wrong = r;
        wrong.knowledge_mode = "historical".into();
        assert!(validate_request(&wrong).is_err());
    }
}
