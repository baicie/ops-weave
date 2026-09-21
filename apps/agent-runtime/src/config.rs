use crate::error::AppError;
use std::{env, net::SocketAddr, path::PathBuf};

#[derive(Clone, PartialEq, Eq)]
pub enum Mode {
    Closed,
    Demo,
}
#[derive(Clone)]
pub struct Config {
    pub mode: Mode,
    pub listen: SocketAddr,
    pub dev_token: Option<String>,
    pub skill_dir: PathBuf,
    pub provider: String,
    pub model: Option<String>,
    pub max_concurrency: usize,
}
impl Config {
    pub fn from_env() -> Result<Self, AppError> {
        let mode = match env::var("OPSWEAVE_MODE").unwrap_or_else(|_|"closed".into()).as_str() {
            "closed" => Mode::Closed,
            "demo" => Mode::Demo,
            _ => return Err(AppError::Configuration("Only closed and demo are implemented; production requires trusted OIDC integration".into())),
        };
        let listen: SocketAddr = env::var("OPSWEAVE_LISTEN")
            .unwrap_or_else(|_| "127.0.0.1:8090".into())
            .parse()
            .map_err(|_| AppError::Configuration("Invalid listen address".into()))?;
        // Demo auth and synthetic data must never be accidentally exposed on a public listener.
        if mode == Mode::Demo && !listen.ip().is_loopback() {
            return Err(AppError::Configuration(
                "Demo mode requires a loopback listener".into(),
            ));
        }
        let token = env::var("OPSWEAVE_DEV_TOKEN").ok();
        if mode == Mode::Demo && token.as_ref().is_none_or(|v| v.len() < 32 || v.trim() != v) {
            return Err(AppError::Configuration(
                "Set OPSWEAVE_DEV_TOKEN to a random token of at least 32 bytes".into(),
            ));
        }
        let provider = env::var("OPSWEAVE_PROVIDER").unwrap_or_else(|_| "mock".into());
        if provider != "mock" && provider != "rig-openai" {
            return Err(AppError::Configuration(
                "Unknown provider; no implicit fallback".into(),
            ));
        }
        if provider != "mock"
            && env::var("OPSWEAVE_ALLOW_MODEL_EGRESS").ok().as_deref() != Some("true")
        {
            return Err(AppError::Configuration(
                "External model access requires explicit OPSWEAVE_ALLOW_MODEL_EGRESS=true".into(),
            ));
        }
        Ok(Self {
            mode,
            listen,
            dev_token: token,
            provider,
            model: env::var("OPSWEAVE_LLM_MODEL").ok(),
            skill_dir: env::var_os("OPSWEAVE_SKILL_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("extensions/skills/incident-diagnosis")),
            max_concurrency: 4,
        })
    }
}
