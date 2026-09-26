use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use chrono::{DateTime, Duration, Utc};
use http_body_util::BodyExt;
use opsweave_agent_runtime::{
    api::{self, AppState},
    config::{Config, Mode},
    context,
    domain::*,
    policies::{self, CallBudget},
    skills::LoadedSkill,
};
use std::{collections::BTreeSet, path::PathBuf, sync::Arc};
use tower::ServiceExt;

fn t() -> DateTime<Utc> {
    "2020-01-01T01:00:00Z".parse().unwrap()
}
fn request() -> DiagnoseRequest {
    DiagnoseRequest {
        incident_id: "inc-demo".into(),
        question: "为什么延迟升高？".into(),
        time_range: TimeRange {
            from: t() - Duration::hours(1),
            to: t(),
        },
        as_of: t(),
    }
}
fn principal() -> Principal {
    Principal {
        tenant_id: "tenant-demo".into(),
        user_id: "test".into(),
        permissions: [
            "ai.diagnose",
            "incident.read",
            "metric.read",
            "evidence.read",
        ]
        .into_iter()
        .map(String::from)
        .collect(),
        allowed_incidents: BTreeSet::from(["inc-demo".into()]),
    }
}
fn ev() -> Evidence {
    Evidence {
        id: "ev-1".into(),
        tenant_id: "tenant-demo".into(),
        incident_id: "inc-demo".into(),
        kind: "metric".into(),
        summary: "synthetic sample".into(),
        observed_at: t(),
        available_at: t(),
        expires_at: t() + Duration::hours(2),
        source_ref: "fixture://metric".into(),
        trust: "untrusted_data".into(),
    }
}
fn skill_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../extensions/skills/incident-diagnosis")
}
fn app(mode: Mode) -> axum::Router {
    api::router(
        AppState::new(Config {
            mode,
            listen: "127.0.0.1:8090".parse().unwrap(),
            dev_token: Some("a".repeat(48)),
            skill_dir: skill_dir(),
            provider: "mock".into(),
            model: None,
            max_concurrency: 2,
            platform_url: None,
            runtime_key: None,
        })
        .unwrap(),
    )
}
fn post(token: bool, value: serde_json::Value) -> Request<Body> {
    let mut builder = Request::builder()
        .method("POST")
        .uri("/api/v1/diagnoses")
        .header("content-type", "application/json");
    if token {
        builder = builder.header("authorization", format!("Bearer {}", "a".repeat(48)));
    }
    builder.body(Body::from(value.to_string())).unwrap()
}
#[test]
fn rejects_cross_tenant_evidence() {
    let mut e = ev();
    e.tenant_id = "other".into();
    assert!(context::build(&principal(), &request(), "r".into(), vec![e], t(), 16).is_err());
}
#[test]
fn rejects_cross_incident_evidence() {
    let mut e = ev();
    e.incident_id = "inc-other".into();
    assert!(context::build(&principal(), &request(), "r".into(), vec![e], t(), 16).is_err());
}
#[test]
fn checks_expiry_at_access_not_historical_cutoff() {
    let c = context::build(
        &principal(),
        &request(),
        "r".into(),
        vec![ev()],
        t() + Duration::hours(3),
        16,
    )
    .unwrap();
    assert!(c.evidence.is_empty());
    assert_eq!(c.missing[0].reason, "expired_at_access");
}
#[test]
fn prevents_future_knowledge_leakage() {
    let mut e = ev();
    e.available_at = t() + Duration::seconds(1);
    let c = context::build(
        &principal(),
        &request(),
        "r".into(),
        vec![e],
        t() + Duration::minutes(1),
        16,
    )
    .unwrap();
    assert!(c.evidence.is_empty());
}
#[test]
fn rejects_duplicate_ids() {
    assert!(context::build(
        &principal(),
        &request(),
        "r".into(),
        vec![ev(), ev()],
        t(),
        16
    )
    .is_err());
}
#[test]
fn filters_outside_window() {
    let mut e = ev();
    e.observed_at = t() - Duration::hours(2);
    let c = context::build(&principal(), &request(), "r".into(), vec![e], t(), 16).unwrap();
    assert!(c.evidence.is_empty());
}
#[test]
fn preserves_explicit_gap() {
    let c = context::build(&principal(), &request(), "r".into(), vec![], t(), 16).unwrap();
    assert_eq!(c.missing[0].reason, "no_usable_evidence");
}
#[test]
fn requires_resource_permission() {
    let mut p = principal();
    p.permissions.remove("evidence.read");
    assert!(context::build(&p, &request(), "r".into(), vec![ev()], t(), 16).is_err());
}
#[test]
fn request_rejects_inverted_window() {
    let mut r = request();
    r.time_range.from = r.time_range.to;
    assert!(policies::validate_request(&r).is_err());
}
#[test]
fn request_rejects_large_window() {
    let mut r = request();
    r.time_range.from = t() - Duration::days(2);
    assert!(policies::validate_request(&r).is_err());
}
#[test]
fn request_rejects_empty_question() {
    let mut r = request();
    r.question = " ".into();
    assert!(policies::validate_request(&r).is_err());
}
#[test]
fn atomic_budget_exhausts() {
    let b = CallBudget::new(1);
    assert!(b.consume().is_ok());
    assert!(b.consume().is_err());
}
#[tokio::test]
async fn parallel_calls_share_budget() {
    let b = Arc::new(CallBudget::new(1));
    let c = b.clone();
    let a = tokio::spawn(async move { c.consume().is_ok() });
    let d = b.clone();
    let z = tokio::spawn(async move { d.consume().is_ok() });
    assert_ne!(a.await.unwrap(), z.await.unwrap());
}
#[test]
fn terminal_run_cannot_resume() {
    assert!(!RunState::Succeeded.can_transition_to(&RunState::Running));
    assert!(RunState::Queued.can_transition_to(&RunState::Running));
}
#[test]
fn skill_package_loads() {
    let s = LoadedSkill::load(&skill_dir()).unwrap();
    assert_eq!(s.spec.max_tool_calls, 2);
    assert!(s.digest.starts_with("sha256:"));
}
#[test]
fn model_json_must_not_contain_actions() {
    let s = LoadedSkill::load(&skill_dir()).unwrap();
    assert!(s
        .parse_output(
            r#"{"summary":"ok","findings":[],"missingData":[],"limitations":[],"execute":"shell"}"#
        )
        .is_err());
}
#[test]
fn model_markdown_is_rejected() {
    let s = LoadedSkill::load(&skill_dir()).unwrap();
    assert!(s.parse_output("```json\n{}\n```").is_err());
}
#[test]
fn fabricated_evidence_rejected() {
    let c = context::build(&principal(), &request(), "r".into(), vec![ev()], t(), 16).unwrap();
    let i = InsightDraft {
        summary: "x".into(),
        findings: vec![Finding {
            kind: FindingKind::Hypothesis,
            statement: "x".into(),
            evidence_refs: vec!["invented".into()],
        }],
        missing_data: vec![],
        limitations: vec![],
    };
    assert!(context::validate_insight(&i, &c, t()).is_err());
}
#[test]
fn evidence_rechecked_after_model_call() {
    let c = context::build(&principal(), &request(), "r".into(), vec![ev()], t(), 16).unwrap();
    let i = InsightDraft {
        summary: "x".into(),
        findings: vec![Finding {
            kind: FindingKind::Observation,
            statement: "x".into(),
            evidence_refs: vec!["ev-1".into()],
        }],
        missing_data: vec![],
        limitations: vec![],
    };
    assert!(context::validate_insight(&i, &c, t() + Duration::hours(3)).is_err());
}
#[tokio::test]
async fn health_is_not_readiness() {
    let a = app(Mode::Closed);
    assert_eq!(
        a.clone()
            .oneshot(
                Request::builder()
                    .uri("/healthz")
                    .body(Body::empty())
                    .unwrap()
            )
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        a.oneshot(
            Request::builder()
                .uri("/readyz")
                .body(Body::empty())
                .unwrap()
        )
        .await
        .unwrap()
        .status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
}
#[tokio::test]
async fn endpoint_requires_token() {
    let res = app(Mode::Demo)
        .oneshot(post(false, serde_json::to_value(request()).unwrap()))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::UNAUTHORIZED);
}
#[tokio::test]
async fn endpoint_rejects_identity_override() {
    let mut value = serde_json::to_value(request()).unwrap();
    value["tenantId"] = serde_json::json!("other");
    let res = app(Mode::Demo).oneshot(post(true, value)).await.unwrap();
    assert_eq!(res.status(), StatusCode::UNPROCESSABLE_ENTITY);
}
#[tokio::test]
async fn endpoint_rejects_other_incident() {
    let mut r = request();
    r.incident_id = "other".into();
    let res = app(Mode::Demo)
        .oneshot(post(true, serde_json::to_value(r).unwrap()))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::FORBIDDEN);
}
#[tokio::test]
async fn demo_returns_explicit_fixture_result() {
    let res = app(Mode::Demo)
        .oneshot(post(true, serde_json::to_value(request()).unwrap()))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let bytes = res.into_body().collect().await.unwrap().to_bytes();
    let result: RunResult = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(result.data_mode, "synthetic-fixture");
    assert_eq!(result.verification, "reference_integrity_only");
    assert_eq!(result.context.evidence.len(), 2);
}
#[tokio::test]
async fn closed_rejects_business() {
    let res = app(Mode::Closed)
        .oneshot(post(true, serde_json::to_value(request()).unwrap()))
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::SERVICE_UNAVAILABLE);
}
