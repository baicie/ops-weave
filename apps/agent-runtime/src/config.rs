use crate::error::AppError;
use std::{env, net::SocketAddr, path::PathBuf};

#[derive(Clone, PartialEq, Eq)]
pub enum Mode {
    Closed,
    Demo,
    PlatformDev,
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
    pub platform_url: Option<String>,
    pub runtime_key: Option<String>,
}
impl Config {
    pub fn from_env() -> Result<Self, AppError> {
        let mode = match env::var("OPSWEAVE_MODE").unwrap_or_else(|_|"closed".into()).as_str() {
            "closed" => Mode::Closed,
            "demo" => Mode::Demo,
            "platform-dev" => Mode::PlatformDev,
            _ => return Err(AppError::Configuration("Only closed, demo and platform-dev are implemented; production requires trusted OIDC integration".into())),
        };
        let listen: SocketAddr = env::var("OPSWEAVE_LISTEN")
            .unwrap_or_else(|_| "127.0.0.1:8090".into())
            .parse()
            .map_err(|_| AppError::Configuration("Invalid listen address".into()))?;
        // Demo auth and synthetic data must never be accidentally exposed on a public listener.
        if mode != Mode::Closed && !listen.ip().is_loopback() {
            return Err(AppError::Configuration(
                "Development modes require a loopback listener".into(),
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
        if provider != "mock" && mode != Mode::PlatformDev {
            return Err(AppError::Configuration(
                "Paid models require platform-dev spend admission".into(),
            ));
        }
        if provider != "mock"
            && env::var("OPSWEAVE_ALLOW_MODEL_EGRESS").ok().as_deref() != Some("true")
        {
            return Err(AppError::Configuration(
                "External model access requires explicit OPSWEAVE_ALLOW_MODEL_EGRESS=true".into(),
            ));
        }
        let default_skill = if mode == Mode::PlatformDev {
            "extensions/skills/incident-diagnosis-current"
        } else {
            "extensions/skills/incident-diagnosis"
        };
        let platform_url = env::var("OPSWEAVE_PLATFORM_URL").ok();
        let runtime_key = env::var("OPSWEAVE_RUNTIME_KEY").ok();
        if mode == Mode::PlatformDev && (platform_url.is_none() || runtime_key.is_none()) {
            return Err(AppError::Configuration(
                "Platform development mode requires explicit platform URL and Runtime result key"
                    .into(),
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
                .unwrap_or_else(|| PathBuf::from(default_skill)),
            max_concurrency: 4,
            platform_url,
            runtime_key,
        })
    }
}
