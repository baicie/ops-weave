use crate::{
    adapters::{fixture::FixturePlatform, mock_model::MockModel},
    config::{Config, Mode},
    domain::{CurrentDiagnoseRequest, DiagnoseRequest, Principal, RunResult, SavedInsight},
    error::AppError,
    ports::{ModelPort, PlatformReadPort, RuntimeStateStore},
    skills::LoadedSkill,
    workflows,
};
use axum::{
    extract::{DefaultBodyLimit, State},
    http::HeaderMap,
    routing::{get, post},
    Json, Router,
};
use serde_json::{json, Value};
use std::{
    collections::BTreeSet,
    sync::{Arc, Mutex},
    time::Duration,
};
use subtle::ConstantTimeEq;
use tokio::sync::Semaphore;

pub struct AppState {
    pub config: Config,
    pub skill: Option<Arc<LoadedSkill>>,
    pub model: Arc<dyn ModelPort>,
    pub platform: Arc<dyn PlatformReadPort>,
    pub concurrency: Semaphore,
    pub current_platform: Option<crate::adapters::platform_http::PlatformHttp>,
    /// The Runtime's own durable internal state (5d). Absent means nothing is recorded —explicit.
    pub runtime_state: Option<Arc<crate::adapters::journal::JournalRuntimeStateStore>>,
    active_current: Mutex<BTreeSet<(String, String)>>,
}
impl AppState {
    pub fn new(config: Config) -> Result<Arc<Self>, AppError> {
        if config.provider != "mock" && config.mode != Mode::PlatformDev {
            return Err(AppError::Configuration(
                "Paid models require platform spend admission".into(),
            ));
        }
        let model: Arc<dyn ModelPort> = match config.provider.as_str() {
            "mock" => Arc::new(MockModel),
            "rig-openai" => {
                #[cfg(feature = "rig-provider")]
                {
                    Arc::new(crate::adapters::rig_model::RigOpenAiModel::new(
                        config.model.clone().ok_or_else(|| {
                            AppError::Configuration("Set OPSWEAVE_LLM_MODEL".into())
                        })?,
                    )?)
                }
                #[cfg(not(feature = "rig-provider"))]
                {
                    return Err(AppError::Configuration(
                        "Rebuild with --features rig-provider".into(),
                    ));
                }
            }
            _ => return Err(AppError::Configuration("Unknown provider".into())),
        };
        let skill = if config.mode != Mode::Closed {
            let skill = LoadedSkill::load(&config.skill_dir)?;
            let template = if config.mode == Mode::PlatformDev {
                "readonly-current-incident-diagnosis-v1"
            } else {
                "readonly-incident-diagnosis-v1"
            };
            if skill.spec.execution_template != template {
                return Err(AppError::Configuration(
                    "Skill template does not match Runtime mode".into(),
                ));
            }
            Some(Arc::new(skill))
        } else {
            None
        };
        let concurrency = Semaphore::new(config.max_concurrency);
        if config.mode != Mode::Closed && !config.listen.ip().is_loopback() {
            return Err(AppError::Configuration(
                "Development modes require loopback".into(),
            ));
        }
        let current_platform = if config.mode == Mode::PlatformDev {
            Some(
                crate::adapters::platform_http::PlatformHttp::loopback_runtime(
                    config.platform_url.as_deref().ok_or(AppError::Disabled)?,
                    config.runtime_key.as_deref().ok_or(AppError::Disabled)?,
                )?,
            )
        } else {
            None
        };
        // The Runtime's own durable state (5d): a journal directory means every internal step,
        // checkpoint and evidence recheck survives a crash; without it nothing is recorded and the
        // Runtime says so —it never pretends progress was persisted.
        let runtime_state = match config.state_dir.as_ref() {
            Some(dir) => {
                std::fs::create_dir_all(dir).map_err(|_| {
                    AppError::Configuration("Runtime state directory is not writable".into())
                })?;
                Some(Arc::new(
                    crate::adapters::journal::JournalRuntimeStateStore::open(
                        dir.join("runtime-state.jsonl"),
                    )?,
                ))
            }
            None => None,
        };
        Ok(Arc::new(Self {
            config,
            skill,
            model,
            platform: Arc::new(FixturePlatform),
            concurrency,
            current_platform,
            runtime_state,
            active_current: Mutex::new(BTreeSet::new()),
        }))
    }
    fn authenticate(&self, headers: &HeaderMap) -> Result<Principal, AppError> {
        if self.config.mode != Mode::Demo {
            return Err(AppError::Disabled);
        }
        let provided = headers
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.strip_prefix("Bearer "))
            .ok_or(AppError::Unauthorized)?;
        let expected = self
            .config
            .dev_token
            .as_deref()
            .ok_or(AppError::Unauthorized)?;
        if !bool::from(provided.as_bytes().ct_eq(expected.as_bytes())) {
            return Err(AppError::Unauthorized);
        }
        Ok(Principal {
            tenant_id: "tenant-demo".into(),
            user_id: "developer".into(),
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
        })
    }
}
pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/healthz", get(health))
        .route("/readyz", get(ready))
        .route("/api/v1/diagnoses", post(diagnose))
        .route("/api/v1/current-diagnoses", post(diagnose_current))
        .layer(DefaultBodyLimit::max(16384))
        .with_state(state)
}
async fn health() -> Json<Value> {
    Json(json!({"status":"ok","service":"opsweave-agent-runtime"}))
}
async fn ready(State(s): State<Arc<AppState>>) -> Result<Json<Value>, AppError> {
    if s.config.mode == Mode::Closed {
        return Err(AppError::Disabled);
    }
    if s.config.mode == Mode::PlatformDev {
        return Ok(Json(
            json!({"status":"platform-development-only","provider":s.model.name(),"platformReadiness":"not_probed","resultStorage":"platform-owned"}),
        ));
    }
    Ok(Json(
        json!({"status":"demo-only","data":"synthetic-fixture","provider":s.model.name(),"durability":false}),
    ))
}

struct CurrentRunGuard {
    state: Arc<AppState>,
    key: (String, String),
}
impl Drop for CurrentRunGuard {
    fn drop(&mut self) {
        if let Ok(mut active) = self.state.active_current.lock() {
            active.remove(&self.key);
        }
    }
}
async fn diagnose_current(
    State(s): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(r): Json<CurrentDiagnoseRequest>,
) -> Result<Json<SavedInsight>, AppError> {
    if s.config.mode != Mode::PlatformDev {
        return Err(AppError::Disabled);
    }
    crate::current_workflows::validate_request(&r)?;
    if headers.get_all("authorization").iter().count() != 1 {
        return Err(AppError::Unauthorized);
    }
    let token = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or(AppError::Unauthorized)?;
    let _permit = s.concurrency.try_acquire().map_err(|_| AppError::Busy)?;
    tokio::time::timeout(
        Duration::from_secs(60),
        execute_current(s.clone(), &r, token),
    )
    .await
    .map_err(|_| AppError::Timeout)?
}
async fn execute_current(
    s: Arc<AppState>,
    r: &CurrentDiagnoseRequest,
    token: &str,
) -> Result<Json<SavedInsight>, AppError> {
    let read = Arc::new(
        s.current_platform
            .as_ref()
            .ok_or(AppError::Disabled)?
            .open(token, &r.incident_id, &r.time_range)
            .await?,
    );
    let key = (read.principal().tenant_id, r.run_id.clone());
    if !s
        .active_current
        .lock()
        .map_err(|_| AppError::Platform)?
        .insert(key.clone())
    {
        return Err(AppError::Busy);
    }
    let _guard = CurrentRunGuard {
        state: s.clone(),
        key,
    };
    if let Some(saved) = read.find_saved(&r.run_id, &r.question).await? {
        return Ok(Json(saved));
    }
    let model_name = if s.config.provider == "mock" {
        "mock-current-v1"
    } else {
        s.config.model.as_deref().ok_or(AppError::Disabled)?
    };
    // Runtime-owned internal state (5d): every step, checkpoint and evidence recheck of this
    // dispatch is recorded durably when the Runtime was started with a state journal. The recorder
    // is observability —its own failure never fails or re-runs the chain.
    let session: Arc<dyn crate::ports::CurrentReadSessionPort> = read.clone();
    let recorder = match s.runtime_state.clone() {
        Some(store) => Some(Arc::new(
            crate::adapters::journal::RuntimeStateRecorder::start(
                store,
                session.clone(),
                &r.run_id,
            )
            .await?,
        )),
        None => None,
    };
    let session: Arc<dyn crate::ports::CurrentReadSessionPort> = match recorder.as_ref() {
        Some(recorder) => recorder.clone(),
        None => session,
    };
    let outcome = crate::current_workflows::diagnose(
        r,
        s.skill.as_ref().ok_or(AppError::Disabled)?,
        session,
        s.model.clone(),
        model_name,
    )
    .await;
    if let Some(recorder) = recorder.as_ref() {
        recorder
            .mark_status(if outcome.is_ok() {
                crate::domain::AirunStatus::Succeeded
            } else {
                crate::domain::AirunStatus::Failed
            })
            .await;
        finalize_state_report(&s, recorder, token, &r.run_id).await;
    }
    let result = outcome?;
    tracing::info!(run_id=%r.run_id, provider=%s.model.name(), status="saved", "current diagnosis finished");
    Ok(Json(result))
}

/// Deliver one closed attestation of the Runtime's internal state to the platform —at-least-once.
/// The outbox entry is written first; the delivery attempt is then recorded as `Delivered` or
/// `Unknown` (never as a silent success and never dropped). Exactly-once is not claimed: a retry may
/// deliver the same report twice and the platform deduplicates on its request id.
async fn finalize_state_report(
    s: &Arc<AppState>,
    recorder: &Arc<crate::adapters::journal::RuntimeStateRecorder>,
    token: &str,
    run_id: &str,
) {
    let store = match s.runtime_state.as_ref() {
        Some(store) => store,
        None => return,
    };
    let request_id = uuid::Uuid::new_v4().to_string();
    let report = match recorder.report(&request_id).await {
        Ok(Some(report)) => report,
        _ => return,
    };
    let entry = crate::runtime_state::OutboxEntry::enqueue(report.clone(), chrono::Utc::now());
    if store.enqueue(&entry).await.is_err() {
        return;
    }
    let platform = match s.current_platform.as_ref() {
        Some(platform) => platform,
        None => return,
    };
    let delivered = matches!(
        platform.deliver_runtime_state(token, run_id, &report).await,
        Ok(crate::runtime_state::DeliveryState::Delivered)
    );
    if !delivered {
        tracing::warn!(run_id, "runtime state report delivery unconfirmed; the outbox keeps it as Unknown for an explicit retry");
    }
    if let Some(updated) = entry.record_attempt(delivered, chrono::Utc::now()) {
        let _ = store.update_delivery(&request_id, &updated).await;
    }
}
async fn diagnose(
    State(s): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(r): Json<DiagnoseRequest>,
) -> Result<Json<RunResult>, AppError> {
    let principal = s.authenticate(&headers)?;
    let _permit = s.concurrency.try_acquire().map_err(|_| AppError::Busy)?;
    let skill = s.skill.as_ref().ok_or(AppError::Disabled)?;
    let outcome = tokio::time::timeout(
        Duration::from_millis(skill.spec.deadline_ms),
        workflows::diagnose(&principal, &r, skill, s.platform.clone(), s.model.clone()),
    )
    .await
    .map_err(|_| AppError::Timeout)??;
    tracing::info!(run_id=%outcome.run_id, provider=%outcome.model_provider, status="succeeded", "diagnosis finished");
    Ok(Json(outcome))
}
