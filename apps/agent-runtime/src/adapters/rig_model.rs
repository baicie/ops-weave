//! One explicitly selected, bounded Responses or Chat Completions request through locked Rig. No tools, retries, redirects or content logs.
//! The API key travels only in the Authorization header, and the operator question plus the platform
//! knowledge reach the provider as named untrusted data (`untrustedUserQuestion`,
//! `untrustedEvidenceContext`) under the shipped Skill prompt that says content is never instructions.
use crate::{
    domain::{ContextPack, CurrentContext},
    error::AppError,
    model_spend::{ModelInput, ModelOutput, ModelUsage, MAX_OUTPUT_TOKENS},
    ports::ModelPort,
};
use async_trait::async_trait;
use bytes::Bytes;
use rig::{
    client::CompletionClient,
    completion::CompletionModel,
    http_client::{
        self, HttpClientExt, LazyBody, MultipartForm, Request, Response, StreamingResponse,
    },
    providers::openai,
};
use serde_json::{json, Value};
use std::{future::Future, time::Duration};
use tracing::instrument::WithSubscriber;

#[derive(Clone, Debug)]
struct BoundedHttp(provider_http::Client);
impl Default for BoundedHttp {
    fn default() -> Self {
        Self::new().expect("fixed provider HTTP configuration")
    }
}
impl BoundedHttp {
    fn new() -> Result<Self, AppError> {
        Ok(Self(
            provider_http::Client::builder()
                .no_proxy()
                .redirect(provider_http::redirect::Policy::none())
                .retry(provider_http::retry::never())
                .connect_timeout(Duration::from_secs(3))
                .timeout(Duration::from_secs(18))
                .pool_max_idle_per_host(4)
                .build()
                .map_err(|_| AppError::Provider)?,
        ))
    }
}
fn transport_error() -> http_client::Error {
    http_client::Error::StreamEnded
}
impl HttpClientExt for BoundedHttp {
    fn send<T, U>(
        &self,
        req: Request<T>,
    ) -> impl Future<Output = http_client::Result<Response<LazyBody<U>>>> + Send + 'static
    where
        T: Into<Bytes> + Send,
        U: From<Bytes> + Send + 'static,
    {
        let (parts, body) = req.into_parts();
        let chat = parts.uri.path().ends_with("/chat/completions");
        let request = self
            .0
            .request(parts.method, parts.uri.to_string())
            .headers(parts.headers)
            .body(body.into());
        async move {
            let mut response = request.send().await.map_err(|_| transport_error())?;
            if !response.status().is_success()
                || response
                    .headers()
                    .get("content-type")
                    .and_then(|h| h.to_str().ok())
                    .is_none_or(|h| h.split(';').next() != Some("application/json"))
                || response.content_length().is_some_and(|n| n > 262144)
            {
                return Err(transport_error());
            }
            let mut builder = Response::builder().status(response.status());
            *builder.headers_mut().ok_or_else(transport_error)? = response.headers().clone();
            let body: LazyBody<U> = Box::pin(async move {
                let mut data = Vec::new();
                while let Some(chunk) = response.chunk().await.map_err(|_| transport_error())? {
                    if data.len() + chunk.len() > 262144 {
                        return Err(transport_error());
                    }
                    data.extend_from_slice(&chunk);
                }
                if chat {
                    // Inspect the wire object before SDK normalization can drop malformed tool calls.
                    // Preserve valid usage but erase unsupported output; workflow reports charges first.
                    let mut raw: Value =
                        serde_json::from_slice(&data).map_err(|_| transport_error())?;
                    let output = parse_chat_response(&raw).map_err(|_| transport_error())?;
                    if output.text.is_empty() {
                        raw["choices"] = json!([{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":""}}]);
                        data = serde_json::to_vec(&raw).map_err(|_| transport_error())?;
                    }
                }
                Ok(U::from(Bytes::from(data)))
            });
            builder.body(body).map_err(http_client::Error::Protocol)
        }
    }
    // `async fn` cannot express the `'static` future the trait requires, and this adapter never
    // permits multipart bodies: the request is rejected before it leaves the process.
    #[allow(clippy::manual_async_fn)]
    fn send_multipart<U>(
        &self,
        _: Request<MultipartForm>,
    ) -> impl Future<Output = http_client::Result<Response<LazyBody<U>>>> + Send + 'static
    where
        U: From<Bytes> + Send + 'static,
    {
        async { Err(transport_error()) }
    }
    async fn send_streaming<T>(&self, _: Request<T>) -> http_client::Result<StreamingResponse>
    where
        T: Into<Bytes> + Send,
    {
        Err(transport_error())
    }
}
#[derive(Clone, Copy, PartialEq, Eq)]
enum ModelApi {
    Responses,
    ChatCompletions,
}
impl ModelApi {
    fn parse(value: &str) -> Result<Self, AppError> {
        match value {
            "responses" => Ok(Self::Responses),
            "chat-completions" => Ok(Self::ChatCompletions),
            _ => Err(AppError::Configuration(
                "Unknown model API; no protocol fallback".into(),
            )),
        }
    }
}
// No Debug: this adapter owns credentials, never diagnostic output.
pub struct RigOpenAiModel {
    model: String,
    key: String,
    base: String,
    api: ModelApi,
    http: BoundedHttp,
}
impl RigOpenAiModel {
    pub fn new(model: String) -> Result<Self, AppError> {
        let key = std::env::var("OPENAI_API_KEY")
            .map_err(|_| AppError::Configuration("OPENAI_API_KEY is required".into()))?;
        let base =
            std::env::var("OPENAI_BASE_URL").unwrap_or_else(|_| "https://api.openai.com/v1".into());
        let api = ModelApi::parse(
            &std::env::var("OPSWEAVE_MODEL_API").unwrap_or_else(|_| "responses".into()),
        )?;
        let url = provider_http::Url::parse(&base)
            .map_err(|_| AppError::Configuration("Invalid model origin".into()))?;
        if url.scheme() != "https"
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
        {
            return Err(AppError::Configuration(
                "Model origin requires HTTPS and no embedded credentials/query".into(),
            ));
        }
        Self::configured_api(model, &key, &base, api)
    }
    #[cfg(test)]
    fn configured(model: String, key: &str, base: &str) -> Result<Self, AppError> {
        Self::configured_api(model, key, base, ModelApi::Responses)
    }
    fn configured_api(
        model: String,
        key: &str,
        base: &str,
        api: ModelApi,
    ) -> Result<Self, AppError> {
        if model.trim().is_empty() || model.len() > 128 || key.trim().is_empty() {
            return Err(AppError::Configuration(
                "Model and API key are required".into(),
            ));
        }
        Ok(Self {
            model,
            key: key.into(),
            base: base.into(),
            api,
            http: BoundedHttp::new()?,
        })
    }
    #[cfg(test)]
    async fn call(&self, input: &ModelInput) -> Result<ModelOutput, AppError> {
        self.call_scoped(input, None).await
    }
    async fn call_scoped(
        &self,
        input: &ModelInput,
        session: Option<&str>,
    ) -> Result<ModelOutput, AppError> {
        if input.bytes > crate::model_spend::MAX_INPUT_BYTES
            || input.bytes != input.system.len() + input.payload.len()
        {
            return Err(AppError::InvalidOutput);
        }
        let mut headers = provider_http::header::HeaderMap::new();
        headers.insert(
            "user-agent",
            provider_http::header::HeaderValue::from_static("opsweave-agent-runtime/0.1.0"),
        );
        if self.api == ModelApi::ChatCompletions {
            // An opaque platform-issued session; immutable per request, never global mutable state.
            let session = session.ok_or(AppError::Platform)?;
            if uuid::Uuid::parse_str(session).is_err() {
                return Err(AppError::Platform);
            }
            headers.insert(
                "x-opencode-session",
                provider_http::header::HeaderValue::from_str(session)
                    .map_err(|_| AppError::Platform)?,
            );
        }
        let client = openai::Client::builder()
            .api_key(self.key.clone())
            .base_url(&self.base)
            .http_headers(headers)
            .http_client(self.http.clone())
            .build()
            .map_err(|_| AppError::Provider)?;
        // Suppress SDK content diagnostics even with RUST_LOG=trace. No protocol retries.
        match self.api {
            ModelApi::Responses => {
                let model = client.completion_model(&self.model);
                let request = model
                    .completion_request(input.payload.clone())
                    .preamble(input.system.clone())
                    .max_tokens(MAX_OUTPUT_TOKENS)
                    .additional_params(json!({"store":false,"background":false}))
                    .build();
                let response = model
                    .raw_completion(request)
                    .with_subscriber(tracing::subscriber::NoSubscriber::default())
                    .await
                    .map_err(|_| AppError::Provider)?;
                parse_response(
                    &serde_json::to_value(response).map_err(|_| AppError::InvalidOutput)?,
                )
            }
            ModelApi::ChatCompletions => {
                let model = client.completions_api().completion_model(&self.model);
                let request = model.completion_request(input.payload.clone())
                    .preamble(input.system.clone()).max_tokens(MAX_OUTPUT_TOKENS)
                    .additional_params(json!({"stream":false,"n":1,"store":false,"response_format":{"type":"json_object"},"thinking":{"type":"disabled"}})).build();
                let response = model
                    .raw_completion(request)
                    .with_subscriber(tracing::subscriber::NoSubscriber::default())
                    .await
                    .map_err(|_| AppError::Provider)?;
                parse_chat_response(
                    &serde_json::to_value(response).map_err(|_| AppError::InvalidOutput)?,
                )
            }
        }
    }
}
fn parse_chat_response(raw: &Value) -> Result<ModelOutput, AppError> {
    let u = &raw["usage"];
    let number = |key: &str| u[key].as_u64().ok_or(AppError::InvalidOutput);
    let usage = ModelUsage {
        input_tokens: number("prompt_tokens")?,
        output_tokens: number("completion_tokens")?,
        // Optional cache details are counted as uncached for conservative platform pricing.
        cached_input_tokens: match &u["prompt_tokens_details"]["cached_tokens"] {
            Value::Null => 0,
            value => value.as_u64().ok_or(AppError::InvalidOutput)?,
        },
        source: "provider-reported".into(),
    };
    usage.validate("rig-openai")?;
    if number("total_tokens")? != usage.input_tokens + usage.output_tokens {
        return Err(AppError::InvalidOutput);
    }
    let choices = raw["choices"].as_array().ok_or(AppError::InvalidOutput)?;
    let text = if choices.len() == 1
        && choices[0]["index"] == 0
        && choices[0]["finish_reason"] == "stop"
        && choices[0]["message"]["role"] == "assistant"
        && choices[0]["message"]
            .get("refusal")
            .is_none_or(Value::is_null)
        && choices[0]["message"]
            .get("tool_calls")
            .is_none_or(|v| v.is_null() || v.as_array().is_some_and(Vec::is_empty))
        && choices[0]["message"]
            .get("function_call")
            .is_none_or(Value::is_null)
    {
        match &choices[0]["message"]["content"] {
            Value::String(text) => text.clone(),
            Value::Array(parts)
                if parts
                    .iter()
                    .all(|p| p["type"] == "text" && p["text"].is_string()) =>
            {
                parts.iter().filter_map(|p| p["text"].as_str()).collect()
            }
            _ => String::new(),
        }
    } else {
        String::new()
    };
    // Keep valid usage for refusal/truncation/unsupported output so it is reported before rejection.
    Ok(ModelOutput { text, usage })
}

fn parse_response(raw: &Value) -> Result<ModelOutput, AppError> {
    let u = &raw["usage"];
    let number = |key: &str| u[key].as_u64().ok_or(AppError::InvalidOutput);
    let usage = ModelUsage {
        input_tokens: number("input_tokens")?,
        output_tokens: number("output_tokens")?,
        cached_input_tokens: u["input_tokens_details"]["cached_tokens"]
            .as_u64()
            .ok_or(AppError::InvalidOutput)?,
        source: "provider-reported".into(),
    };
    usage.validate("rig-openai")?;
    if number("total_tokens")? != usage.input_tokens + usage.output_tokens {
        return Err(AppError::InvalidOutput);
    }
    let mut text = String::new();
    let mut invalid = raw["status"] != "completed";
    for item in raw["output"].as_array().ok_or(AppError::InvalidOutput)? {
        match item["type"].as_str() {
            Some("reasoning") => {}
            Some("message") => {
                for part in item["content"].as_array().ok_or(AppError::InvalidOutput)? {
                    if part["type"] == "output_text" {
                        text.push_str(part["text"].as_str().ok_or(AppError::InvalidOutput)?);
                    } else {
                        invalid = true;
                    }
                }
            }
            _ => invalid = true,
        }
    }
    if invalid {
        text.clear();
    }
    Ok(ModelOutput { text, usage })
}
#[async_trait]
impl ModelPort for RigOpenAiModel {
    fn name(&self) -> &str {
        "rig-openai"
    }
    async fn generate(&self, _: &str, _: &str, _: &ContextPack) -> Result<String, AppError> {
        Err(AppError::Disabled)
    }
    async fn generate_current(
        &self,
        _: &str,
        _: &str,
        _: &CurrentContext,
    ) -> Result<String, AppError> {
        Err(AppError::Disabled)
    }
    async fn generate_metered(
        &self,
        input: &ModelInput,
        context: &CurrentContext,
    ) -> Result<ModelOutput, AppError> {
        self.call_scoped(input, Some(&context.session_id)).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{routing::post, Json, Router};
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc, Mutex,
    };
    fn response() -> Value {
        json!({"id":"resp_fixture","object":"response","created_at":1700000000,"status":"completed","error":null,"incomplete_details":null,
        "instructions":null,"max_output_tokens":2048,"model":"fixture-model","usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":20},"output_tokens":10,"output_tokens_details":{"reasoning_tokens":2},"total_tokens":110},
        "output":[{"type":"message","id":"msg_fixture","role":"assistant","status":"completed","content":[{"type":"output_text","text":"fixture output","annotations":[]}]}],"tools":[]})
    }
    fn input() -> ModelInput {
        ModelInput {
            system: "fixture system".into(),
            payload: "fixture question".into(),
            bytes: 30,
            digest: "not-used-by-provider".into(),
        }
    }
    async fn server(app: Router) -> (String, tokio::task::JoinHandle<()>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}/v1", listener.local_addr().unwrap());
        (
            base,
            tokio::spawn(async move { axum::serve(listener, app).await.unwrap() }),
        )
    }
    #[tokio::test]
    async fn responses_fixture_preserves_usage_and_enforces_one_nonstored_bounded_request() {
        let requests = Arc::new(Mutex::new(Vec::<(axum::http::HeaderMap, Value)>::new()));
        let seen = requests.clone();
        let (base, handle) = server(Router::new().route(
            "/v1/responses",
            post(
                move |headers: axum::http::HeaderMap, Json(body): Json<Value>| {
                    seen.lock().unwrap().push((headers, body));
                    async { Json(response()) }
                },
            ),
        ))
        .await;
        let key = "fixture-only-not-a-real-key";
        let model = RigOpenAiModel::configured("fixture-model".into(), key, &base).unwrap();
        let output = model.call(&input()).await.unwrap();
        handle.abort();
        assert_eq!(output.text, "fixture output");
        assert_eq!(output.usage.input_tokens, 100);
        assert_eq!(output.usage.output_tokens, 10);
        assert_eq!(output.usage.cached_input_tokens, 20);
        let requests = requests.lock().unwrap();
        assert_eq!(requests.len(), 1);
        let (headers, r) = &requests[0];
        assert_eq!(
            headers["authorization"].to_str().unwrap(),
            format!("Bearer {key}"),
            "the key travels only in the Authorization header"
        );
        assert!(
            !r.to_string().contains(key),
            "the API key never enters the request body"
        );
        assert_eq!(r["max_output_tokens"], 2048);
        assert_eq!(r["store"], false);
        assert_eq!(r["background"], false);
        assert!(r
            .get("tools")
            .is_none_or(|v| v.as_array().is_some_and(Vec::is_empty)));
        assert!(r.get("previous_response_id").is_none());
    }
    #[tokio::test]
    async fn current_knowledge_request_frames_content_as_untrusted_data_without_the_key() {
        let requests = Arc::new(Mutex::new(Vec::<(axum::http::HeaderMap, Value)>::new()));
        let seen = requests.clone();
        let (base, handle) = server(Router::new().route(
            "/v1/responses",
            post(
                move |headers: axum::http::HeaderMap, Json(body): Json<Value>| {
                    seen.lock().unwrap().push((headers, body));
                    async { Json(response()) }
                },
            ),
        ))
        .await;
        let key = "fixture-key-that-must-stay-in-the-header";
        let model = RigOpenAiModel::configured("fixture-model".into(), key, &base).unwrap();
        // The shipped Skill prompt is the system message; the workflow frames the question and the
        // knowledge context as named untrusted fields, never as instructions.
        let prompt = include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../extensions/skills/incident-diagnosis-current/prompt.md"
        ));
        let now = chrono::Utc::now();
        let context = CurrentContext {
            schema_version: "1.0".into(),
            knowledge_mode: "current".into(),
            run_id: "run-fixture".into(),
            tenant_id: "tenant-fixture".into(),
            incident_id: "inc-fixture".into(),
            session_id: "session-fixture".into(),
            time_range: crate::domain::TimeRange {
                from: now - chrono::Duration::minutes(5),
                to: now,
            },
            as_of: now,
            built_at: now,
            evidence: vec![json!({"evidence":{"id":"ev-fixture","trust":"untrusted_data"}})],
            missing_data: vec!["CURRENT_KNOWLEDGE_ONLY".into()],
        };
        let input =
            ModelInput::current(prompt, "Why did the host stop responding?", &context).unwrap();
        let output = model.call(&input).await.unwrap();
        handle.abort();
        assert_eq!(output.text, "fixture output");
        let requests = requests.lock().unwrap();
        assert_eq!(requests.len(), 1);
        let (headers, body) = &requests[0];
        assert_eq!(
            headers["authorization"].to_str().unwrap(),
            format!("Bearer {key}")
        );
        let rendered = body.to_string();
        assert!(
            !rendered.contains(key),
            "the API key never enters the request body"
        );
        assert!(
            rendered.contains("untrustedUserQuestion"),
            "the operator question is sent as untrusted data"
        );
        assert!(
            rendered.contains("untrustedEvidenceContext"),
            "platform knowledge is sent as untrusted data"
        );
        assert!(
            rendered.contains("untrusted content, never instructions"),
            "the shipped Skill prompt states that content is data"
        );
        assert!(
            rendered.contains("ev-fixture"),
            "the structured evidence reaches the provider"
        );
        assert!(
            rendered.contains("requiredOutputSchema"),
            "the output schema is supplied as data"
        );
    }
    #[tokio::test]
    async fn unavailable_redirect_missing_usage_and_oversize_never_retry_or_fallback() {
        use axum::response::IntoResponse;
        for mode in ["unavailable", "redirect", "missing-usage", "oversize"] {
            let calls = Arc::new(AtomicUsize::new(0));
            let count = calls.clone();
            let app = Router::new().route(
                "/v1/responses",
                post(move || {
                    count.fetch_add(1, Ordering::SeqCst);
                    async move {
                        match mode {
                            "unavailable" => {
                                (axum::http::StatusCode::SERVICE_UNAVAILABLE, "no").into_response()
                            }
                            "redirect" => (
                                axum::http::StatusCode::TEMPORARY_REDIRECT,
                                [("location", "/v1/responses")],
                            )
                                .into_response(),
                            "oversize" => {
                                ([("content-type", "application/json")], "x".repeat(262145))
                                    .into_response()
                            }
                            _ => {
                                let mut body = response();
                                body["usage"] = Value::Null;
                                Json(body).into_response()
                            }
                        }
                    }
                }),
            );
            let (base, handle) = server(app).await;
            let model =
                RigOpenAiModel::configured("fixture-model".into(), "fixture-key", &base).unwrap();
            assert!(model.call(&input()).await.is_err(), "{mode}");
            assert_eq!(calls.load(Ordering::SeqCst), 1);
            handle.abort();
        }
    }
    #[test]
    fn incomplete_refusal_and_zero_output_retain_input_charge_while_invalid_usage_is_rejected() {
        let mut body = response();
        body["status"] = json!("incomplete");
        body["output"] = json!([]);
        body["usage"]["output_tokens"] = json!(0);
        body["usage"]["total_tokens"] = json!(100);
        let output = parse_response(&body).unwrap();
        assert!(output.text.is_empty());
        assert_eq!(output.usage.input_tokens, 100);
        assert_eq!(output.usage.output_tokens, 0);
        body["usage"]["input_tokens_details"]["cached_tokens"] = json!(101);
        assert!(parse_response(&body).is_err());
        body = response();
        body["usage"]["total_tokens"] = json!(111);
        assert!(parse_response(&body).is_err());
        body = response();
        body["output"][0]["content"] = json!([{"type":"refusal","refusal":"fixture"}]);
        assert!(parse_response(&body).unwrap().text.is_empty());
    }
    fn chat_response() -> Value {
        json!({"id":"chat-fixture","object":"chat.completion","created":1700000000,"model":"fixture-model",
        "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"fixture output"}}],
        "usage":{"prompt_tokens":100,"completion_tokens":10,"total_tokens":110,"prompt_tokens_details":{"cached_tokens":20}}})
    }
    #[tokio::test]
    async fn chat_fixture_uses_one_request_per_session_with_bounded_output_and_no_secret_in_body() {
        let requests = Arc::new(Mutex::new(Vec::<(axum::http::HeaderMap, Value)>::new()));
        let seen = requests.clone();
        let (base, handle) = server(Router::new().route(
            "/v1/chat/completions",
            post(
                move |headers: axum::http::HeaderMap, Json(body): Json<Value>| {
                    seen.lock().unwrap().push((headers, body));
                    async { Json(chat_response()) }
                },
            ),
        ))
        .await;
        let key = "fixture-only-chat-key";
        let model = RigOpenAiModel::configured_api(
            "fixture-model".into(),
            key,
            &base,
            ModelApi::ChatCompletions,
        )
        .unwrap();
        let a = "74ca3a7e-6441-4355-acbc-de42ca7a4123";
        let b = "9924ab82-354a-4c27-be65-9c13f9b7286a";
        let input = input();
        let (ra, rb) = tokio::join!(
            model.call_scoped(&input, Some(a)),
            model.call_scoped(&input, Some(b))
        );
        for result in [ra, rb] {
            let output = result.unwrap();
            assert_eq!(output.text, "fixture output");
            assert_eq!(output.usage.cached_input_tokens, 20);
        }
        assert!(model.call_scoped(&input, None).await.is_err());
        assert!(model
            .call_scoped(&input, Some("forged-header\r\n"))
            .await
            .is_err());
        handle.abort();
        let requests = requests.lock().unwrap();
        assert_eq!(requests.len(), 2);
        let sessions: std::collections::BTreeSet<_> = requests
            .iter()
            .map(|(h, _)| h["x-opencode-session"].to_str().unwrap())
            .collect();
        assert_eq!(sessions, std::collections::BTreeSet::from([a, b]));
        for (headers, body) in requests.iter() {
            assert_eq!(headers["authorization"], format!("Bearer {key}"));
            assert_eq!(headers["user-agent"], "opsweave-agent-runtime/0.1.0");
            assert!(!body.to_string().contains(key));
            assert_eq!(body["max_tokens"], 2048);
            assert_eq!(body["n"], 1);
            assert_eq!(body["stream"], false);
            assert_eq!(body["store"], false);
            assert_eq!(body["thinking"]["type"], "disabled");
            assert_eq!(body["response_format"]["type"], "json_object");
            assert!(body
                .get("tools")
                .is_none_or(|v| v.as_array().is_some_and(Vec::is_empty)));
            assert!(!body.to_string().contains("previous_response_id"));
        }
    }
    #[test]
    fn chat_usage_is_mandatory_and_incomplete_or_tool_output_retains_charge_without_a_draft() {
        assert!(ModelApi::parse("unknown").is_err());
        let mut body = chat_response();
        body["usage"]["prompt_tokens_details"] = Value::Null;
        assert_eq!(
            parse_chat_response(&body)
                .unwrap()
                .usage
                .cached_input_tokens,
            0
        );
        for field in ["prompt_tokens", "completion_tokens", "total_tokens"] {
            let mut invalid = body.clone();
            invalid["usage"][field] = Value::Null;
            assert!(parse_chat_response(&invalid).is_err());
        }
        for (field, value) in [
            ("prompt_tokens", 0),
            ("completion_tokens", 2049),
            ("total_tokens", 111),
        ] {
            let mut invalid = body.clone();
            invalid["usage"][field] = json!(value);
            assert!(parse_chat_response(&invalid).is_err());
        }
        for mode in ["length", "refusal", "tools", "function", "many", "role"] {
            let mut invalid = body.clone();
            match mode {
                "length" => invalid["choices"][0]["finish_reason"] = json!("length"),
                "refusal" => invalid["choices"][0]["message"]["refusal"] = json!("no"),
                "tools" => {
                    invalid["choices"][0]["message"]["tool_calls"] = json!([{"id":"fixture"}])
                }
                "function" => {
                    invalid["choices"][0]["message"]["function_call"] = json!({"name":"shell"})
                }
                "many" => {
                    invalid["choices"] = json!([invalid["choices"][0], invalid["choices"][0]])
                }
                _ => invalid["choices"][0]["message"]["role"] = json!("user"),
            }
            let result = parse_chat_response(&invalid).unwrap();
            assert!(result.text.is_empty(), "{mode}");
            assert_eq!(result.usage.input_tokens, 100);
        }
    }
    #[tokio::test]
    async fn chat_errors_never_retry_or_fallback_to_responses() {
        use axum::response::IntoResponse;
        for mode in ["unavailable", "redirect", "missing-usage", "oversize"] {
            let calls = Arc::new(AtomicUsize::new(0));
            let count = calls.clone();
            let (base, handle) = server(Router::new().fallback(post(move || {
                count.fetch_add(1, Ordering::SeqCst);
                async move {
                    match mode {
                        "unavailable" => {
                            (axum::http::StatusCode::SERVICE_UNAVAILABLE, "fixture").into_response()
                        }
                        "redirect" => (
                            axum::http::StatusCode::TEMPORARY_REDIRECT,
                            [("location", "/v1/responses")],
                        )
                            .into_response(),
                        "oversize" => ([("content-type", "application/json")], "x".repeat(262145))
                            .into_response(),
                        _ => {
                            let mut body = chat_response();
                            body["usage"] = Value::Null;
                            Json(body).into_response()
                        }
                    }
                }
            })))
            .await;
            let model = RigOpenAiModel::configured_api(
                "fixture-model".into(),
                "fixture-key",
                &base,
                ModelApi::ChatCompletions,
            )
            .unwrap();
            assert!(
                model
                    .call_scoped(&input(), Some("74ca3a7e-6441-4355-acbc-de42ca7a4123"))
                    .await
                    .is_err(),
                "{mode}"
            );
            assert_eq!(calls.load(Ordering::SeqCst), 1);
            handle.abort();
        }
    }
    #[tokio::test]
    async fn chat_wire_tools_cannot_be_hidden_by_sdk_normalization() {
        for field in ["tool_calls", "function_call", "refusal"] {
            let (base, handle) = server(Router::new().route(
                "/v1/chat/completions",
                post(move || async move {
                    let mut body = chat_response();
                    body["choices"][0]["message"][field] = match field {
                        "tool_calls" => json!([{"id":"malformed-tool-call"}]),
                        "function_call" => json!({"name":"unsupported"}),
                        _ => json!("fixture-refusal"),
                    };
                    Json(body)
                }),
            ))
            .await;
            let model = RigOpenAiModel::configured_api(
                "fixture-model".into(),
                "fixture-key",
                &base,
                ModelApi::ChatCompletions,
            )
            .unwrap();
            let output = model
                .call_scoped(&input(), Some("74ca3a7e-6441-4355-acbc-de42ca7a4123"))
                .await
                .unwrap();
            assert!(output.text.is_empty(), "{field}");
            assert_eq!(output.usage.input_tokens, 100);
            handle.abort();
        }
    }
}
