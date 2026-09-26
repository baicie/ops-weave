//! Explicit loopback development adapter for the Java read-session boundary.
//! No application-wide credential, redirect, proxy, remote schema retrieval or fixture fallback.
use crate::{
    domain::{Evidence, InsightSubmission, Principal, SavedInsight, TimeRange},
    error::AppError,
    policies::CallBudget,
};
use chrono::{DateTime, Duration as ChronoDuration, Utc};
use reqwest::{
    header::{HeaderValue, AUTHORIZATION, CONTENT_TYPE},
    Client, Url,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{collections::BTreeSet, net::IpAddr, sync::Arc, time::Duration};
use tokio::sync::Semaphore;
use uuid::Uuid;

#[derive(Clone)]
pub struct PlatformHttp(Arc<Inner>);
struct Inner {
    base: Url,
    client: Client,
    sessions: jsonschema::Validator,
    results: jsonschema::Validator,
    insights: jsonschema::Validator,
    spend: jsonschema::Validator,
    runtime_key: Option<HeaderValue>,
    concurrency: Semaphore,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Session {
    schema_version: String,
    id: String,
    tenant_id: String,
    subject_id: String,
    incident_id: String,
    incident_version: u64,
    entity_ids: BTreeSet<String>,
    query_window: TimeRange,
    knowledge_mode: String,
    created_at: DateTime<Utc>,
    deadline_at: DateTime<Utc>,
    max_tool_calls: usize,
    used_calls: usize,
    allowed_tools: BTreeSet<String>,
    storage: String,
    policy_version: String,
    permissions: BTreeSet<String>,
}
/// Created only by a successful, schema-checked Java session response. Never accept one from a caller/model.
pub struct BoundRead {
    http: PlatformHttp,
    authorization: HeaderValue,
    session: Session,
    budget: CallBudget,
}
/// Canonical contract value plus typed evidence, obtained only after scope and time checks.
pub use crate::domain::PlatformSnapshot as Snapshot;

// Every accepted reference is compiled into the binary from contracts. No filesystem or network resolution.
fn schema(name: &str) -> Result<Value, AppError> {
    macro_rules! source {
        ($path:literal) => {
            include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../../contracts/schemas/",
                $path
            ))
        };
    }
    let text = match name {
        "tool-read-session.schema.json" => source!("v1/tool-read-session.schema.json"),
        "tool-result.schema.json" => source!("v1/tool-result.schema.json"),
        "platform-evidence.schema.json" => source!("v1/platform-evidence.schema.json"),
        "tool-time-window.schema.json" => source!("v1/tool-time-window.schema.json"),
        "incident-tool-data.schema.json" => source!("v1/incident-tool-data.schema.json"),
        "metric-tool-data.schema.json" => source!("v1/metric-tool-data.schema.json"),
        "../v2/evidence.schema.json" => source!("v2/evidence.schema.json"),
        "ai-insight-result.schema.json" => source!("v1/ai-insight-result.schema.json"),
        "ai-insight.schema.json" => source!("v1/ai-insight.schema.json"),
        "insight-draft.schema.json" => source!("v1/insight-draft.schema.json"),
        "model-spend-result.schema.json" => source!("v1/model-spend-result.schema.json"),
        "model-spend-record.schema.json" => source!("v1/model-spend-record.schema.json"),
        "model-spend-policy.schema.json" => source!("v1/model-spend-policy.schema.json"),
        "model-spend-usage.schema.json" => source!("v1/model-spend-usage.schema.json"),
        _ => {
            return Err(AppError::Configuration(
                "Unsupported built-in contract reference".into(),
            ))
        }
    };
    serde_json::from_str(text)
        .map_err(|_| AppError::Configuration("Invalid built-in contract".into()))
}
fn resolve(value: Value, depth: usize) -> Result<Value, AppError> {
    if depth > 32 {
        return Err(AppError::Configuration(
            "Contract reference depth exceeded".into(),
        ));
    }
    match value {
        Value::Object(mut fields) => {
            fields.remove("$schema");
            if let Some(reference) = fields.remove("$ref") {
                let target = resolve(
                    schema(
                        reference
                            .as_str()
                            .ok_or_else(|| AppError::Configuration("Invalid reference".into()))?,
                    )?,
                    depth + 1,
                )?;
                let siblings = resolve(Value::Object(fields), depth + 1)?;
                Ok(json!({"allOf": [target, siblings]}))
            } else {
                for value in fields.values_mut() {
                    *value = resolve(value.take(), depth + 1)?;
                }
                Ok(Value::Object(fields))
            }
        }
        Value::Array(items) => Ok(Value::Array(
            items
                .into_iter()
                .map(|v| resolve(v, depth + 1))
                .collect::<Result<_, _>>()?,
        )),
        other => Ok(other),
    }
}
fn validator(name: &str) -> Result<jsonschema::Validator, AppError> {
    jsonschema::draft202012::options()
        .should_validate_formats(true)
        .build(&resolve(schema(name)?, 0)?)
        .map_err(|_| AppError::Configuration("Cannot compile platform contract".into()))
}
impl PlatformHttp {
    pub fn loopback(base: &str) -> Result<Self, AppError> {
        let base =
            Url::parse(base).map_err(|_| AppError::Configuration("Invalid platform URL".into()))?;
        let host = base.host_str().unwrap_or("").trim_matches(['[', ']']);
        if base.scheme() != "http"
            || !host.parse::<IpAddr>().is_ok_and(|ip| ip.is_loopback())
            || !base.username().is_empty()
            || base.password().is_some()
            || base.path() != "/"
            || base.query().is_some()
            || base.fragment().is_some()
        {
            return Err(AppError::Configuration(
                "Platform development adapter requires a plain loopback HTTP origin".into(),
            ));
        }
        let client = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .retry(reqwest::retry::never())
            .no_gzip()
            .no_brotli()
            .no_deflate()
            .no_zstd()
            .connect_timeout(Duration::from_secs(2))
            .timeout(Duration::from_secs(16))
            .pool_max_idle_per_host(4)
            .build()
            .map_err(|_| AppError::Platform)?;
        Ok(Self(Arc::new(Inner {
            base,
            client,
            sessions: validator("tool-read-session.schema.json")?,
            results: validator("tool-result.schema.json")?,
            insights: validator("ai-insight-result.schema.json")?,
            spend: validator("model-spend-result.schema.json")?,
            runtime_key: None,
            concurrency: Semaphore::new(4),
        })))
    }
    pub fn loopback_runtime(base: &str, key: &str) -> Result<Self, AppError> {
        if key.len() < 32 || key.len() > 4096 || !key.bytes().all(|c| c.is_ascii_graphic()) {
            return Err(AppError::Configuration(
                "Set an explicit local Runtime result key".into(),
            ));
        }
        let mut value = Self::loopback(base)?;
        let mut header = HeaderValue::from_str(key)
            .map_err(|_| AppError::Configuration("Invalid Runtime key".into()))?;
        header.set_sensitive(true);
        Arc::get_mut(&mut value.0)
            .ok_or(AppError::Platform)?
            .runtime_key = Some(header);
        Ok(value)
    }
    /// Forwards only this request's credential to the fixed Java origin; Java constructs the principal.
    pub async fn open(
        &self,
        bearer: &str,
        incident_id: &str,
        window: &TimeRange,
    ) -> Result<BoundRead, AppError> {
        if bearer.len() < 32 || bearer.len() > 4096 || !bearer.bytes().all(|c| c.is_ascii_graphic())
        {
            return Err(AppError::Unauthorized);
        }
        if Uuid::parse_str(incident_id).is_err()
            || window.from >= window.to
            || window.to > Utc::now()
            || window.from.timestamp() < 0
            || window.to.timestamp() > 9999999999
            || window.to - window.from > ChronoDuration::hours(1)
            || window.from.timestamp_subsec_nanos() != 0
            || window.to.timestamp_subsec_nanos() != 0
        {
            return Err(AppError::Invalid(
                "Require an Incident UUID and a past whole-second window of at most one hour"
                    .into(),
            ));
        }
        let mut authorization = HeaderValue::from_str(&format!("Bearer {bearer}"))
            .map_err(|_| AppError::Unauthorized)?;
        authorization.set_sensitive(true);
        let started = Utc::now();
        let value = self
            .post(
                "api/v1/ai/read-sessions",
                &authorization,
                None,
                json!({"incidentId": incident_id, "timeRange": window, "knowledgeMode": "current"}),
            )
            .await?;
        if !self.0.sessions.is_valid(&value) {
            return Err(AppError::Platform);
        }
        let session: Session = serde_json::from_value(value).map_err(|_| AppError::Platform)?;
        // Wall clocks share this local machine. Reject future/backdated sessions instead of rewriting availability.
        if session.incident_id != incident_id
            || !same_window(&session.query_window, window)
            || session.created_at < started
            || session.created_at > Utc::now()
            || session.deadline_at != session.created_at + ChronoDuration::seconds(60)
            || session.deadline_at <= Utc::now()
            || session.used_calls != 0
            || session.schema_version != "1.0"
            || session.knowledge_mode != "current"
            || session.policy_version != "readonly-diagnosis-v1"
            || session.max_tool_calls != 4
        {
            return Err(AppError::Platform);
        }
        for permission in [
            "ai.diagnose",
            "incident.read",
            "entity.read",
            "metric.read",
            "evidence.read",
        ] {
            if !session.permissions.contains(permission) {
                return Err(AppError::Forbidden);
            }
        }
        Ok(BoundRead {
            http: self.clone(),
            authorization,
            budget: CallBudget::new(session.max_tool_calls),
            session,
        })
    }
    async fn post(
        &self,
        path: &str,
        authorization: &HeaderValue,
        session: Option<&str>,
        body: Value,
    ) -> Result<Value, AppError> {
        self.send(path, authorization, session, Some(body)).await
    }
    async fn send(
        &self,
        path: &str,
        authorization: &HeaderValue,
        session: Option<&str>,
        body: Option<Value>,
    ) -> Result<Value, AppError> {
        let _permit = self
            .0
            .concurrency
            .try_acquire()
            .map_err(|_| AppError::Busy)?;
        let url = self.0.base.join(path).map_err(|_| AppError::Platform)?;
        let mut request = match body {
            Some(body) => self.0.client.post(url).json(&body),
            None => self.0.client.get(url),
        }
        .header(AUTHORIZATION, authorization.clone());
        if let Some(id) = session {
            request = request.header("X-OpsWeave-Read-Session", id);
        }
        if matches!(
            path,
            "api/v1/ai/insights"
                | "api/v1/ai/model-calls/reservations"
                | "api/v1/ai/model-calls/reports"
        ) {
            request = request.header(
                "X-OpsWeave-Runtime-Key",
                self.0
                    .runtime_key
                    .as_ref()
                    .ok_or(AppError::Disabled)?
                    .clone(),
            );
        }
        let max_bytes = if path.starts_with("api/v1/ai/insights") {
            131072
        } else {
            32768
        };
        let mut response = request.send().await.map_err(http_error)?;
        match response.status().as_u16() {
            200 => {}
            401 => return Err(AppError::Unauthorized),
            403 => return Err(AppError::Forbidden),
            404 => return Err(AppError::NotFound),
            409 => return Err(AppError::InputChanged),
            410 => return Err(AppError::Expired),
            429 => return Err(AppError::Busy),
            504 => return Err(AppError::Timeout),
            _ => return Err(AppError::Platform),
        }
        if response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .is_none_or(|v| v.split(';').next() != Some("application/json"))
            || response
                .content_length()
                .is_some_and(|size| size > max_bytes as u64)
        {
            return Err(AppError::Platform);
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(http_error)? {
            if bytes.len() + chunk.len() > max_bytes {
                return Err(AppError::Platform);
            }
            bytes.extend_from_slice(&chunk);
        }
        serde_json::from_slice(&bytes).map_err(|_| AppError::Platform)
    }
}
impl BoundRead {
    fn check_spend(&self, value: &Value) -> Result<(), AppError> {
        use crate::model_spend::{ModelUsage, MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS};
        if !self.http.0.spend.is_valid(value) || value["storage"] != self.session.storage {
            return Err(AppError::Platform);
        }
        let r = &value["record"];
        let p = &r["policy"];
        let n = |v: &Value| v.as_u64().ok_or(AppError::Platform);
        let estimate = |input: u64, output: u64| -> Result<u64, AppError> {
            Ok((input * n(&p["inputMicrosPerMillion"])?
                + output * n(&p["outputMicrosPerMillion"])?)
            .div_ceil(1_000_000))
        };
        let reserved = estimate(MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS)?;
        if r["tenantId"] != self.session.tenant_id
            || r["subjectId"] != self.session.subject_id
            || r["sessionId"] != self.session.id
            || r["incidentId"] != self.session.incident_id
            || date(&r["reservedAt"])? < self.session.created_at
            || date(&r["reservedAt"])? > Utc::now()
            || date(&r["deadlineAt"])? != self.session.deadline_at
            || r["reservedMicros"] != reserved
            || reserved > n(&p["maxCallMicros"])?
            || n(&p["maxCallMicros"])? > n(&p["dailyMicros"])?
        {
            return Err(AppError::Platform);
        }
        let cost = if r["state"] == "REPORTED" {
            let usage: ModelUsage =
                serde_json::from_value(r["usage"].clone()).map_err(|_| AppError::Platform)?;
            usage.validate(p["provider"].as_str().ok_or(AppError::Platform)?)?;
            if date(&r["reportedAt"])? < date(&r["reservedAt"])?
                || date(&r["reportedAt"])? > Utc::now()
            {
                return Err(AppError::Platform);
            }
            let cost = estimate(usage.input_tokens, usage.output_tokens)?;
            if r["estimatedMicros"] != cost {
                return Err(AppError::Platform);
            }
            cost
        } else {
            reserved
        };
        if r["accountedMicros"] != cost {
            return Err(AppError::Platform);
        }
        Ok(())
    }
    pub async fn reserve_model(
        &self,
        run: &str,
        input: &crate::model_spend::ModelInput,
    ) -> Result<crate::model_spend::ModelPermit, AppError> {
        if Uuid::parse_str(run).is_err() || Utc::now() >= self.session.deadline_at {
            return Err(AppError::Expired);
        }
        let result=self.http.post("api/v1/ai/model-calls/reservations", &self.authorization, None,
            json!({"runId":run,"sessionId":self.session.id,"inputDigest":input.digest,"inputBytes":input.bytes})).await?;
        self.check_spend(&result)?;
        let r = &result["record"];
        if r["state"] != "RESERVED"
            || r["runId"] != run
            || r["inputDigest"] != input.digest
            || r["inputBytes"] != input.bytes
        {
            return Err(AppError::Platform);
        }
        Ok(crate::model_spend::ModelPermit {
            run_id: run.into(),
            session_id: self.session.id.clone(),
            provider: r["policy"]["provider"]
                .as_str()
                .ok_or(AppError::Platform)?
                .into(),
            model: r["policy"]["model"]
                .as_str()
                .ok_or(AppError::Platform)?
                .into(),
            input_digest: input.digest.clone(),
            input_bytes: input.bytes,
            deadline_at: date(&r["deadlineAt"])?,
            record: r.clone(),
        })
    }
    pub async fn report_model(
        &self,
        permit: &crate::model_spend::ModelPermit,
        usage: &crate::model_spend::ModelUsage,
    ) -> Result<(), AppError> {
        usage.validate(&permit.provider)?;
        let result = self
            .http
            .post(
                "api/v1/ai/model-calls/reports",
                &self.authorization,
                None,
                json!({"runId":permit.run_id,"sessionId":permit.session_id,"usage":usage}),
            )
            .await?;
        self.check_spend(&result)?;
        let r = &result["record"];
        if r["state"] != "REPORTED"
            || r["usage"] != serde_json::to_value(usage).map_err(|_| AppError::Platform)?
        {
            return Err(AppError::Platform);
        }
        for (key, value) in permit.record.as_object().ok_or(AppError::Platform)? {
            if ![
                "state",
                "usage",
                "reportedAt",
                "estimatedMicros",
                "accountedMicros",
            ]
            .contains(&key.as_str())
                && r[key] != *value
            {
                return Err(AppError::Platform);
            }
        }
        Ok(())
    }
    pub fn principal(&self) -> Principal {
        Principal {
            tenant_id: self.session.tenant_id.clone(),
            user_id: self.session.subject_id.clone(),
            permissions: self.session.permissions.clone(),
            allowed_incidents: BTreeSet::from([self.session.incident_id.clone()]),
        }
    }
    pub fn session_id(&self) -> &str {
        &self.session.id
    }
    pub fn storage(&self) -> &str {
        &self.session.storage
    }
    pub async fn save(&self, input: &InsightSubmission) -> Result<SavedInsight, AppError> {
        if input.session_id != self.session.id
            || !self.session.permissions.contains("ai.insight.read")
        {
            return Err(AppError::Forbidden);
        }
        let body = serde_json::to_value(input).map_err(|_| AppError::InvalidOutput)?;
        if serde_json::to_vec(&body)
            .map_err(|_| AppError::InvalidOutput)?
            .len()
            > 65536
        {
            return Err(AppError::InvalidOutput);
        }
        let remaining = (self.session.deadline_at - Utc::now())
            .to_std()
            .map_err(|_| AppError::Expired)?;
        let result = tokio::time::timeout(
            remaining,
            self.http
                .post("api/v1/ai/insights", &self.authorization, None, body),
        )
        .await
        .map_err(|_| AppError::Timeout)??;
        if !self.http.0.insights.is_valid(&result) {
            return Err(AppError::Platform);
        }
        let record = &result["record"];
        let entities: BTreeSet<String> =
            serde_json::from_value(record["entityIds"].clone()).map_err(|_| AppError::Platform)?;
        let window: TimeRange = serde_json::from_value(record["queryWindow"].clone())
            .map_err(|_| AppError::Platform)?;
        if record["id"] != input.run_id
            || record["sessionId"] != self.session.id
            || record["tenantId"] != self.session.tenant_id
            || record["subjectId"] != self.session.subject_id
            || record["incidentId"] != self.session.incident_id
            || record["incidentVersion"] != self.session.incident_version
            || entities != self.session.entity_ids
            || !same_window(&window, &self.session.query_window)
            || record["question"] != input.question
            || record["skill"]
                != serde_json::to_value(&input.skill).map_err(|_| AppError::InvalidOutput)?
            || record["model"]
                != serde_json::to_value(&input.model).map_err(|_| AppError::InvalidOutput)?
            || record["evidenceIds"] != json!(input.evidence_ids)
            || date(&record["asOf"])? != input.as_of
            || date(&record["builtAt"])? != input.built_at
            || date(&record["completedAt"])? != input.completed_at
            || date(&record["savedAt"])? < input.completed_at
            || date(&record["savedAt"])? > Utc::now()
            || date(&record["expiresAt"])? <= Utc::now()
            || record["insight"]["summary"] != input.insight.summary
            || record["insight"]["findings"]
                != serde_json::to_value(&input.insight.findings)
                    .map_err(|_| AppError::InvalidOutput)?
        {
            return Err(AppError::Platform);
        }
        serde_json::from_value(result).map_err(|_| AppError::Platform)
    }
    pub async fn find_saved(
        &self,
        run_id: &str,
        question: &str,
    ) -> Result<Option<SavedInsight>, AppError> {
        let id = Uuid::parse_str(run_id).map_err(|_| AppError::Invalid("Invalid run ID".into()))?;
        let result = match self
            .http
            .send(
                &format!("api/v1/ai/insights/{id}"),
                &self.authorization,
                None,
                None,
            )
            .await
        {
            Err(AppError::NotFound) => return Ok(None),
            result => result?,
        };
        if !self.http.0.insights.is_valid(&result) {
            return Err(AppError::Platform);
        }
        let record = &result["record"];
        let window: TimeRange = serde_json::from_value(record["queryWindow"].clone())
            .map_err(|_| AppError::Platform)?;
        if record["id"] != run_id
            || record["tenantId"] != self.session.tenant_id
            || record["subjectId"] != self.session.subject_id
            || record["incidentId"] != self.session.incident_id
            || record["question"] != question
            || !same_window(&window, &self.session.query_window)
        {
            return Err(AppError::InputChanged);
        }
        if date(&record["expiresAt"])? <= Utc::now() {
            return Err(AppError::Expired);
        }
        Ok(Some(
            serde_json::from_value(result).map_err(|_| AppError::Platform)?,
        ))
    }
    pub async fn incident(&self) -> Result<Snapshot, AppError> {
        self.call(
            "incident.get",
            json!({"incidentId": self.session.incident_id}),
            Some("incident"),
        )
        .await
    }
    pub async fn metric(&self, key: &str, max_points: usize) -> Result<Snapshot, AppError> {
        if key.is_empty()
            || key.len() > 128
            || !key
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b"_.:/%-".contains(&c))
            || !(1..=500).contains(&max_points)
        {
            return Err(AppError::Invalid(
                "Invalid metric key or point limit".into(),
            ));
        }
        let snapshot = self
            .call(
                "metric.summary",
                json!({"incidentId": self.session.incident_id, "metric": key,
            "timeRange": self.session.query_window, "maxPoints": max_points}),
                Some("metric"),
            )
            .await?;
        if snapshot.document["data"]["metricKey"] != key
            || snapshot.document["data"]["maxPoints"] != max_points
            || snapshot.document["data"]["sampleCount"]
                .as_u64()
                .is_none_or(|n| n > max_points as u64)
        {
            return Err(AppError::Platform);
        }
        Ok(snapshot)
    }
    pub async fn recheck(&self, original: &Snapshot) -> Result<(), AppError> {
        if original.document["sessionId"] != self.session.id {
            return Err(AppError::Forbidden);
        }
        let snapshot = self
            .call(
                "evidence.get",
                json!({"evidenceId": original.evidence.id}),
                Some(&original.evidence.kind),
            )
            .await?;
        if snapshot.document != original.document {
            return Err(AppError::InputChanged);
        }
        Ok(())
    }
    async fn call(
        &self,
        tool: &str,
        body: Value,
        kind: Option<&str>,
    ) -> Result<Snapshot, AppError> {
        if Utc::now() >= self.session.deadline_at {
            return Err(AppError::Expired);
        }
        if !self
            .session
            .allowed_tools
            .contains(&format!("{tool}@2.0.0"))
        {
            return Err(AppError::Forbidden);
        }
        self.budget.consume().map_err(|_| AppError::Busy)?;
        let remaining = (self.session.deadline_at - Utc::now())
            .to_std()
            .map_err(|_| AppError::Expired)?;
        let result = tokio::time::timeout(
            remaining,
            self.http.post(
                &format!("api/v1/tools/{tool}/2.0.0"),
                &self.authorization,
                Some(&self.session.id),
                body,
            ),
        )
        .await
        .map_err(|_| AppError::Timeout)??;
        if !self.http.0.results.is_valid(&result) {
            return Err(AppError::Platform);
        }
        let document = result["data"].clone();
        let evidence: Evidence =
            serde_json::from_value(document["evidence"].clone()).map_err(|_| AppError::Platform)?;
        let window: TimeRange = serde_json::from_value(document["queryWindow"].clone())
            .map_err(|_| AppError::Platform)?;
        let entities: BTreeSet<String> = serde_json::from_value(document["entityIds"].clone())
            .map_err(|_| AppError::Platform)?;
        let now = Utc::now();
        if now >= self.session.deadline_at || evidence.expires_at <= now {
            return Err(AppError::Expired);
        }
        if document["sessionId"] != self.session.id
            || evidence.tenant_id != self.session.tenant_id
            || evidence.incident_id != self.session.incident_id
            || document["incidentVersion"] != self.session.incident_version
            || entities != self.session.entity_ids
            || !same_window(&window, &self.session.query_window)
            || evidence.available_at < self.session.created_at
            || evidence.available_at > now
            || evidence.observed_at > evidence.available_at
            || evidence.expires_at != evidence.available_at + ChronoDuration::hours(24)
            || evidence.source_ref != format!("/api/v1/ai/evidence/{}", evidence.id)
            || kind.is_some_and(|kind| evidence.kind != kind)
            || document["producerTool"]
                != if evidence.kind == "incident" {
                    "incident.get@2.0.0"
                } else {
                    "metric.summary@2.0.0"
                }
            || result["evidenceRefs"] != json!([evidence.id])
            || result["warnings"] != document["warnings"]
            || result["status"] != "partial"
        {
            return Err(AppError::Platform);
        }
        if evidence.kind == "metric" {
            let mut count = 0;
            let mut seen = BTreeSet::new();
            let mut modes = BTreeSet::new();
            for entity in document["data"]["entities"]
                .as_array()
                .ok_or(AppError::Platform)?
            {
                let id = entity["entityId"].as_str().ok_or(AppError::Platform)?;
                if !entities.contains(id) || !seen.insert(id) {
                    return Err(AppError::Platform);
                }
                for series in entity["series"].as_array().ok_or(AppError::Platform)? {
                    count += series["count"].as_u64().ok_or(AppError::Platform)?;
                    if series["unit"] != document["data"]["unit"] {
                        return Err(AppError::Platform);
                    }
                    modes.insert(series["dataMode"].as_str().ok_or(AppError::Platform)?);
                    let first = date(&series["firstAt"])?;
                    let last = date(&series["lastAt"])?;
                    if first < window.from || last > window.to || first > last {
                        return Err(AppError::Platform);
                    }
                }
            }
            if document["data"]["sampleCount"] != count {
                return Err(AppError::Platform);
            }
            if modes.is_empty() {
                modes.insert("unknown");
            }
            let declared: BTreeSet<&str> = document["dataModes"]
                .as_array()
                .ok_or(AppError::Platform)?
                .iter()
                .map(|v| v.as_str().ok_or(AppError::Platform))
                .collect::<Result<_, _>>()?;
            if modes != declared {
                return Err(AppError::Platform);
            }
        } else if document["data"]["version"] != self.session.incident_version {
            return Err(AppError::Platform);
        }
        Ok(Snapshot { document, evidence })
    }
}
fn date(value: &Value) -> Result<DateTime<Utc>, AppError> {
    serde_json::from_value(value.clone()).map_err(|_| AppError::Platform)
}
fn same_window(a: &TimeRange, b: &TimeRange) -> bool {
    a.from == b.from && a.to == b.to
}
fn http_error(error: reqwest::Error) -> AppError {
    if error.is_timeout() {
        AppError::Timeout
    } else {
        AppError::Platform
    }
}

#[async_trait::async_trait]
impl crate::ports::CurrentReadSessionPort for BoundRead {
    fn principal(&self) -> Principal {
        BoundRead::principal(self)
    }
    fn session_id(&self) -> &str {
        BoundRead::session_id(self)
    }
    fn window(&self) -> &TimeRange {
        &self.session.query_window
    }
    async fn incident(&self) -> Result<Snapshot, AppError> {
        BoundRead::incident(self).await
    }
    async fn metric(&self) -> Result<Snapshot, AppError> {
        BoundRead::metric(self, "host.cpu.usage.user", 500).await
    }
    async fn recheck(&self, value: &Snapshot) -> Result<(), AppError> {
        BoundRead::recheck(self, value).await
    }
    async fn save(&self, value: &InsightSubmission) -> Result<SavedInsight, AppError> {
        BoundRead::save(self, value).await
    }
    async fn reserve_model(
        &self,
        run: &str,
        input: &crate::model_spend::ModelInput,
    ) -> Result<crate::model_spend::ModelPermit, AppError> {
        BoundRead::reserve_model(self, run, input).await
    }
    async fn report_model(
        &self,
        permit: &crate::model_spend::ModelPermit,
        usage: &crate::model_spend::ModelUsage,
    ) -> Result<(), AppError> {
        BoundRead::report_model(self, permit, usage).await
    }
}
