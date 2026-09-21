use crate::{
    adapters::{fixture::FixturePlatform, mock_model::MockModel},
    config::{Config, Mode},
    domain::{DiagnoseRequest, Principal, RunResult},
    error::AppError,
    ports::{ModelPort, PlatformReadPort},
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
use std::{collections::BTreeSet, sync::Arc, time::Duration};
use subtle::ConstantTimeEq;
use tokio::sync::Semaphore;

pub struct AppState {
    pub config: Config,
    pub skill: Option<Arc<LoadedSkill>>,
    pub model: Arc<dyn ModelPort>,
    pub platform: Arc<dyn PlatformReadPort>,
    pub concurrency: Semaphore,
}
impl AppState {
    pub fn new(config: Config) -> Result<Arc<Self>, AppError> {
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
        let skill = if config.mode == Mode::Demo {
            Some(Arc::new(LoadedSkill::load(&config.skill_dir)?))
        } else {
            None
        };
        let concurrency = Semaphore::new(config.max_concurrency);
        Ok(Arc::new(Self {
            config,
            skill,
            model,
            platform: Arc::new(FixturePlatform),
            concurrency,
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
    Ok(Json(
        json!({"status":"demo-only","data":"synthetic-fixture","provider":s.model.name(),"durability":false}),
    ))
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
