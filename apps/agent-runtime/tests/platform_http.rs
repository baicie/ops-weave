use axum::{
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use chrono::{Duration, Utc};
use opsweave_agent_runtime::{
    adapters::platform_http::PlatformHttp, domain::TimeRange, error::AppError,
};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc, Mutex,
    },
};
use uuid::Uuid;

const TOKEN: &str = "local-test-credential-01234567890123456789";
const INCIDENT: &str = "8bc68f45-ab9f-38ad-9778-db96536f9426";
const ENTITY: &str = "f0fbf389-79bb-34a5-9e66-8e9393d82a09";
struct Fake {
    mode: Mutex<String>,
    session: Mutex<Value>,
    documents: Mutex<HashMap<String, Value>>,
    calls: AtomicUsize,
    saved: Mutex<Value>,
    spend: Mutex<Value>,
}
struct Server {
    base: String,
    fake: Arc<Fake>,
    task: tokio::task::JoinHandle<()>,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}
async fn server() -> Server {
    let fake = Arc::new(Fake {
        mode: Mutex::new(String::new()),
        session: Mutex::new(Value::Null),
        documents: Mutex::new(HashMap::new()),
        calls: AtomicUsize::new(0),
        saved: Mutex::new(Value::Null),
        spend: Mutex::new(Value::Null),
    });
    let router = Router::new()
        .route("/api/v1/ai/read-sessions", post(open))
        .route("/api/v1/tools/{tool}/2.0.0", post(tool))
        .route("/api/v1/ai/insights", post(save))
        .route("/api/v1/ai/insights/{id}", get(lookup))
        .route("/api/v1/ai/model-calls/{operation}", post(spend))
        .with_state(fake.clone());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    Server { base, fake, task }
}
fn auth(headers: &HeaderMap) -> bool {
    headers.get("authorization").and_then(|v| v.to_str().ok()) == Some(&format!("Bearer {TOKEN}"))
}
const RUNTIME_KEY: &str = "runtime-attestation-local-testing-only-0123456789";
async fn spend(
    State(fake): State<Arc<Fake>>,
    Path(operation): Path<String>,
    headers: HeaderMap,
    Json(input): Json<Value>,
) -> Response {
    if !auth(&headers)
        || headers
            .get("x-opsweave-runtime-key")
            .and_then(|v| v.to_str().ok())
            != Some(RUNTIME_KEY)
    {
        return StatusCode::FORBIDDEN.into_response();
    }
    let s = fake.session.lock().unwrap().clone();
    let mode = fake.mode.lock().unwrap().clone();
    if mode == "spend-budget" {
        return StatusCode::TOO_MANY_REQUESTS.into_response();
    }
    let mut r = if operation == "reservations" {
        let mut r: Value = serde_json::from_str(include_str!(
            "../../../contracts/examples/model-spend-record.json"
        ))
        .unwrap();
        for key in ["runId", "sessionId", "inputDigest", "inputBytes"] {
            r[key] = input[key].clone();
        }
        for key in ["tenantId", "subjectId", "incidentId", "deadlineAt"] {
            r[key] = s[key].clone();
        }
        r["reservedAt"] = json!(Utc::now());
        r["reportedAt"] = Value::Null;
        r["usage"] = Value::Null;
        r["state"] = json!("RESERVED");
        r["estimatedMicros"] = Value::Null;
        r["policy"] = json!({"provider":"mock-deterministic","model":"mock-current-v1","priceVersion":"mock-no-charge","inputMicrosPerMillion":0,"outputMicrosPerMillion":0,"maxCallMicros":0,"dailyMicros":0});
        r["reservedMicros"] = json!(0);
        r["accountedMicros"] = json!(0);
        r
    } else {
        let mut r = fake.spend.lock().unwrap().clone();
        r["reportedAt"] = json!(Utc::now());
        r["usage"] = input["usage"].clone();
        r["state"] = json!("REPORTED");
        r["estimatedMicros"] = json!(0);
        r
    };
    match mode.as_str() {
        "spend-tenant" => r["tenantId"] = json!("other"),
        "spend-run" => r["runId"] = json!(Uuid::new_v4()),
        "spend-digest" => r["inputDigest"] = json!(format!("sha256:{}", "0".repeat(64))),
        "spend-cost" => r["reservedMicros"] = json!(1),
        "spend-ceiling" => r["maxOutputTokens"] = json!(2049),
        "report-changed" if operation == "reports" => r["inputBytes"] = json!(5),
        _ => {}
    }
    *fake.spend.lock().unwrap() = r.clone();
    Json(json!({"storage":"postgres","record":r})).into_response()
}
async fn save(
    State(fake): State<Arc<Fake>>,
    headers: HeaderMap,
    Json(input): Json<Value>,
) -> Response {
    if !auth(&headers)
        || headers
            .get("x-opsweave-runtime-key")
            .and_then(|v| v.to_str().ok())
            != Some(RUNTIME_KEY)
    {
        return StatusCode::FORBIDDEN.into_response();
    }
    let mode = fake.mode.lock().unwrap().clone();
    if mode == "save-failure" {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    }
    let session = fake.session.lock().unwrap().clone();
    let mut record = input.clone();
    let fields = record.as_object_mut().unwrap();
    fields.remove("runId");
    fields.insert("id".into(), input["runId"].clone());
    for field in [
        "tenantId",
        "subjectId",
        "incidentId",
        "incidentVersion",
        "entityIds",
        "queryWindow",
    ] {
        fields.insert(field.into(), session[field].clone());
    }
    fields.insert("schemaVersion".into(), json!("1.0"));
    fields.insert("knowledgeMode".into(), json!("current"));
    fields.insert("savedAt".into(), json!(Utc::now()));
    fields.insert("expiresAt".into(), json!(Utc::now() + Duration::hours(1)));
    fields.insert("dataModes".into(), json!(["labeled-fixture"]));
    fields.insert("warnings".into(), json!(["CURRENT_KNOWLEDGE_ONLY"]));
    fields.insert("verification".into(), json!("reference_integrity_only"));
    match mode.as_str() {
        "save-tenant" => record["tenantId"] = json!("another-tenant"),
        "save-output" => record["insight"]["summary"] = json!("silently replaced"),
        "save-expiry" => record["expiresAt"] = json!(Utc::now() - Duration::seconds(1)),
        "save-actions" => record["insight"]["actions"] = json!(["shell"]),
        _ => {}
    }
    let saved = json!({"storage":"memory", "record":record});
    *fake.saved.lock().unwrap() = saved.clone();
    Json(saved).into_response()
}
async fn lookup(
    State(fake): State<Arc<Fake>>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Response {
    assert!(headers.get("x-opsweave-runtime-key").is_none());
    if !auth(&headers) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let saved = fake.saved.lock().unwrap().clone();
    if saved["record"]["id"].as_str() != Some(id.as_str()) {
        return StatusCode::NOT_FOUND.into_response();
    }
    Json(saved).into_response()
}
async fn open(
    State(fake): State<Arc<Fake>>,
    headers: HeaderMap,
    Json(request): Json<Value>,
) -> Response {
    assert!(headers.get("x-opsweave-runtime-key").is_none());
    if !auth(&headers) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let mode = fake.mode.lock().unwrap().clone();
    if mode == "redirect" {
        return (
            StatusCode::TEMPORARY_REDIRECT,
            [("location", "/must-not-follow")],
        )
            .into_response();
    }
    if mode == "oversize" {
        return ([("content-type", "application/json")], " ".repeat(40000)).into_response();
    }
    if mode == "html" {
        return ([("content-type", "text/html")], "not JSON").into_response();
    }
    if mode == "error" {
        return (StatusCode::SERVICE_UNAVAILABLE, "private upstream content").into_response();
    }
    let now = Utc::now();
    let mut session = json!({"schemaVersion":"1.0", "id":Uuid::new_v4(), "tenantId":"tenant-platform-test", "subjectId":"java-authenticated-user",
        "incidentId":request["incidentId"], "incidentVersion":2, "entityIds":[ENTITY], "queryWindow":request["timeRange"],
        "knowledgeMode":"current", "createdAt":now, "deadlineAt":now + Duration::seconds(60), "maxToolCalls":4, "usedCalls":0,
        "allowedTools":["incident.get@2.0.0", "metric.summary@2.0.0", "evidence.get@2.0.0"], "storage":"postgres", "policyVersion":"readonly-diagnosis-v1",
        "permissions":["ai.diagnose", "incident.read", "entity.read", "metric.read", "evidence.read", "ai.insight.read"]});
    match mode.as_str() {
        "session-owner" => session["subjectId"] = json!(""),
        "session-window" => session["queryWindow"]["from"] = json!(now - Duration::days(1)),
        "session-time" => session["createdAt"] = json!(now - Duration::days(1)),
        "session-tools" => session["allowedTools"][0] = json!("shell@1.0.0"),
        "session-budget" => session["maxToolCalls"] = json!(400),
        "session-permission" => session["permissions"] = json!(["ai.diagnose"]),
        "session-historical" => session["knowledgeMode"] = json!("historical"),
        _ => {}
    }
    *fake.session.lock().unwrap() = session.clone();
    Json(session).into_response()
}
async fn tool(
    State(fake): State<Arc<Fake>>,
    Path(tool): Path<String>,
    headers: HeaderMap,
    Json(request): Json<Value>,
) -> Response {
    fake.calls.fetch_add(1, Ordering::SeqCst);
    if !auth(&headers) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let session = fake.session.lock().unwrap().clone();
    if headers
        .get("x-opsweave-read-session")
        .and_then(|v| v.to_str().ok())
        != session["id"].as_str()
    {
        return StatusCode::NOT_FOUND.into_response();
    }
    let mode = fake.mode.lock().unwrap().clone();
    if mode == "revoked" {
        return StatusCode::FORBIDDEN.into_response();
    }
    if mode == "changed" {
        return StatusCode::CONFLICT.into_response();
    }
    if mode == "expired" {
        return StatusCode::GONE.into_response();
    }
    let mut documents = fake.documents.lock().unwrap();
    let mut doc = if tool == "evidence.get" {
        documents
            .get(request["evidenceId"].as_str().unwrap())
            .unwrap()
            .clone()
    } else {
        let mut doc: Value = serde_json::from_str(include_str!(
            "../../../contracts/examples/platform-evidence.json"
        ))
        .unwrap();
        let now = Utc::now();
        let id = Uuid::new_v4().to_string();
        doc["sessionId"] = session["id"].clone();
        doc["incidentVersion"] = json!(2);
        doc["entityIds"] = json!([ENTITY]);
        doc["queryWindow"] = session["queryWindow"].clone();
        doc["evidence"]["tenantId"] = session["tenantId"].clone();
        doc["evidence"]["incidentId"] = session["incidentId"].clone();
        doc["evidence"]["id"] = json!(id);
        doc["evidence"]["sourceRef"] = json!(format!("/api/v1/ai/evidence/{id}"));
        doc["evidence"]["availableAt"] = json!(now);
        doc["evidence"]["observedAt"] = json!(now - Duration::seconds(1));
        doc["evidence"]["expiresAt"] = json!(now + Duration::hours(24));
        doc["data"]["version"] = json!(2);
        if tool == "metric.summary" {
            doc["evidence"]["kind"] = json!("metric");
            doc["producerTool"] = json!("metric.summary@2.0.0");
            doc["warnings"] = json!(["CURRENT_KNOWLEDGE_ONLY", "METRIC_INGEST_TIME_UNAVAILABLE"]);
            doc["data"] = json!({"metricKey": request["metric"], "definitionVersion":1, "unit":"1", "metricType":"GAUGE", "maxPoints":request["maxPoints"], "sampleCount":2,
                "entities":[{"entityId":ENTITY, "status":"AVAILABLE", "series":[{"sourceInstanceId":"zabbix-1", "dataMode":"labeled-fixture", "externalItemId":"20001", "mappingRevision":1,
                    "unit":"1", "dimensions":{"mode":"user"}, "count":2, "min":"0.31", "max":"0.4", "mean":"0.355", "last":"0.4",
                    "firstAt":session["queryWindow"]["from"], "lastAt":session["queryWindow"]["to"]}]}]});
        }
        documents.insert(id, doc.clone());
        doc
    };
    match mode.as_str() {
        "tenant" => doc["evidence"]["tenantId"] = json!("other"),
        "incident" => doc["evidence"]["incidentId"] = json!(Uuid::new_v4()),
        "source-ref" => doc["evidence"]["sourceRef"] = json!("http://127.0.0.1/admin"),
        "future" => doc["evidence"]["availableAt"] = json!(Utc::now() + Duration::days(1)),
        "stale" => doc["evidence"]["expiresAt"] = json!(Utc::now() - Duration::seconds(1)),
        "version" => doc["incidentVersion"] = json!(3),
        "entity" => doc["data"]["entities"][0]["entityId"] = json!(Uuid::new_v4()),
        "count" => doc["data"]["sampleCount"] = json!(3),
        "unit" => doc["data"]["entities"][0]["series"][0]["unit"] = json!("other"),
        "fields" => doc["overrideTenant"] = json!("other"),
        "rewrite" => doc["evidence"]["summary"] = json!("mutable snapshot replacement"),
        _ => {}
    }
    Json(json!({"status":"partial", "evidenceRefs":[doc["evidence"]["id"]], "warnings":doc["warnings"], "data":doc, "truncated":false})).into_response()
}
fn window() -> TimeRange {
    let to = chrono::DateTime::from_timestamp(Utc::now().timestamp() - 1, 0).unwrap();
    TimeRange {
        from: to - Duration::minutes(15),
        to,
    }
}

#[tokio::test]
async fn reads_java_identity_parallel_snapshots_and_rechecks_shared_budget() {
    let server = server().await;
    let http = PlatformHttp::loopback(&server.base).unwrap();
    let read = http.open(TOKEN, INCIDENT, &window()).await.unwrap();
    assert_eq!(read.principal().tenant_id, "tenant-platform-test");
    assert_eq!(read.principal().user_id, "java-authenticated-user");
    assert_eq!(read.principal().allowed_incidents.len(), 1);
    assert_eq!(read.storage(), "postgres");
    let (incident, metric) =
        tokio::try_join!(read.incident(), read.metric("host.cpu.usage.user", 500)).unwrap();
    assert_eq!(metric.document()["data"]["sampleCount"], 2);
    tokio::try_join!(read.recheck(&incident), read.recheck(&metric)).unwrap();
    assert!(matches!(read.incident().await, Err(AppError::Busy)));
    assert_eq!(server.fake.calls.load(Ordering::SeqCst), 4);
}
#[tokio::test]
async fn transport_fails_closed_without_retry_or_fallback() {
    for mode in ["redirect", "oversize", "html", "error"] {
        let server = server().await;
        *server.fake.mode.lock().unwrap() = mode.into();
        let http = PlatformHttp::loopback(&server.base).unwrap();
        assert!(
            matches!(
                http.open(TOKEN, INCIDENT, &window()).await,
                Err(AppError::Platform)
            ),
            "{mode}"
        );
    }
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    drop(listener);
    assert!(matches!(
        PlatformHttp::loopback(&base)
            .unwrap()
            .open(TOKEN, INCIDENT, &window())
            .await,
        Err(AppError::Platform | AppError::Timeout)
    ));
}
#[tokio::test]
async fn session_contract_and_trusted_bounds_are_required() {
    for mode in [
        "session-owner",
        "session-window",
        "session-time",
        "session-tools",
        "session-budget",
        "session-historical",
        "session-permission",
    ] {
        let server = server().await;
        *server.fake.mode.lock().unwrap() = mode.into();
        assert!(
            PlatformHttp::loopback(&server.base)
                .unwrap()
                .open(TOKEN, INCIDENT, &window())
                .await
                .is_err(),
            "{mode}"
        );
    }
}
#[tokio::test]
async fn snapshot_scope_time_and_contract_cannot_be_replaced() {
    for mode in [
        "tenant",
        "incident",
        "source-ref",
        "future",
        "stale",
        "version",
        "fields",
    ] {
        let server = server().await;
        let read = PlatformHttp::loopback(&server.base)
            .unwrap()
            .open(TOKEN, INCIDENT, &window())
            .await
            .unwrap();
        *server.fake.mode.lock().unwrap() = mode.into();
        assert!(read.incident().await.is_err(), "{mode}");
    }
}
#[tokio::test]
async fn metric_payload_respects_bound_entities_samples_and_units() {
    for mode in ["entity", "count", "unit"] {
        let server = server().await;
        let read = PlatformHttp::loopback(&server.base)
            .unwrap()
            .open(TOKEN, INCIDENT, &window())
            .await
            .unwrap();
        *server.fake.mode.lock().unwrap() = mode.into();
        assert!(
            read.metric("host.cpu.usage.user", 500).await.is_err(),
            "{mode}"
        );
    }
}
#[tokio::test]
async fn final_recheck_rejects_revoked_changed_expired_or_rewritten_inputs() {
    for mode in ["revoked", "changed", "expired", "rewrite"] {
        let server = server().await;
        let read = PlatformHttp::loopback(&server.base)
            .unwrap()
            .open(TOKEN, INCIDENT, &window())
            .await
            .unwrap();
        let snapshot = read.incident().await.unwrap();
        *server.fake.mode.lock().unwrap() = mode.into();
        assert!(read.recheck(&snapshot).await.is_err(), "{mode}");
    }
}
#[test]
fn only_explicit_loopback_origins_are_accepted() {
    for base in [
        "https://127.0.0.1",
        "http://localhost",
        "http://example.com",
        "http://0.0.0.0",
        "http://127.0.0.1/path",
        "http://user:secret@127.0.0.1",
        "http://127.0.0.1?token=x",
        "http://127.0.0.1/#fragment",
    ] {
        assert!(PlatformHttp::loopback(base).is_err(), "{base}");
    }
}

#[tokio::test]
async fn current_workflow_saves_attested_receipt_and_lookup_does_not_call_tools() {
    use opsweave_agent_runtime::{
        adapters::mock_model::MockModel, current_workflows, domain::CurrentDiagnoseRequest,
        skills::LoadedSkill,
    };
    let server = server().await;
    let http = PlatformHttp::loopback_runtime(&server.base, RUNTIME_KEY).unwrap();
    let request = CurrentDiagnoseRequest {
        run_id: Uuid::new_v4().to_string(),
        incident_id: INCIDENT.into(),
        question: "Current fixture evidence".into(),
        time_range: window(),
        knowledge_mode: "current".into(),
    };
    let read = Arc::new(
        http.open(TOKEN, INCIDENT, &request.time_range)
            .await
            .unwrap(),
    );
    assert!(read
        .find_saved(&request.run_id, &request.question)
        .await
        .unwrap()
        .is_none());
    let skill = LoadedSkill::load(
        &std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../extensions/skills/incident-diagnosis-current"),
    )
    .unwrap();
    let saved = current_workflows::diagnose(
        &request,
        &skill,
        read.clone(),
        Arc::new(MockModel),
        "mock-current-v1",
    )
    .await
    .unwrap();
    assert_eq!(saved.storage, "memory");
    assert_eq!(server.fake.calls.load(Ordering::SeqCst), 4);
    assert_eq!(
        read.find_saved(&request.run_id, &request.question)
            .await
            .unwrap()
            .unwrap()
            .record,
        saved.record
    );
    assert!(read
        .find_saved(&request.run_id, "changed question")
        .await
        .is_err());
    assert_eq!(server.fake.calls.load(Ordering::SeqCst), 4);
}

#[tokio::test]
async fn current_workflow_never_accepts_failed_or_replaced_save_receipts() {
    use opsweave_agent_runtime::{
        adapters::mock_model::MockModel, current_workflows, domain::CurrentDiagnoseRequest,
        skills::LoadedSkill,
    };
    let skill = LoadedSkill::load(
        &std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../extensions/skills/incident-diagnosis-current"),
    )
    .unwrap();
    for mode in [
        "save-failure",
        "save-tenant",
        "save-output",
        "save-expiry",
        "save-actions",
        "spend-budget",
        "spend-tenant",
        "spend-run",
        "spend-digest",
        "spend-cost",
        "spend-ceiling",
        "report-changed",
    ] {
        let server = server().await;
        *server.fake.mode.lock().unwrap() = mode.into();
        let request = CurrentDiagnoseRequest {
            run_id: Uuid::new_v4().to_string(),
            incident_id: INCIDENT.into(),
            question: "Current fixture evidence".into(),
            time_range: window(),
            knowledge_mode: "current".into(),
        };
        let read = Arc::new(
            PlatformHttp::loopback_runtime(&server.base, RUNTIME_KEY)
                .unwrap()
                .open(TOKEN, INCIDENT, &request.time_range)
                .await
                .unwrap(),
        );
        assert!(
            current_workflows::diagnose(
                &request,
                &skill,
                read,
                Arc::new(MockModel),
                "mock-current-v1"
            )
            .await
            .is_err(),
            "{mode}"
        );
        assert_eq!(
            server.fake.calls.load(Ordering::SeqCst),
            if mode.starts_with("spend-") || mode == "report-changed" {
                2
            } else {
                4
            },
            "{mode}"
        );
    }
}
