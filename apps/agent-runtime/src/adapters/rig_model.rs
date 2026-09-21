//! Optional provider adapter. Verify with `cargo check --features rig-provider`.
//! No raw platform credentials, action tools or content telemetry are passed to Rig.
use crate::{domain::ContextPack, error::AppError, ports::ModelPort};
use async_trait::async_trait;
use rig::{agent::AgentBuilder, prelude::*, providers::openai};
pub struct RigOpenAiModel {
    model: String,
}
impl RigOpenAiModel {
    pub fn new(model: String) -> Result<Self, AppError> {
        if model.trim().is_empty()
            || std::env::var("OPENAI_API_KEY")
                .map(|v| v.trim().is_empty())
                .unwrap_or(true)
        {
            return Err(AppError::Configuration(
                "Model name and OPENAI_API_KEY are required".into(),
            ));
        }
        Ok(Self { model })
    }
}
#[async_trait]
impl ModelPort for RigOpenAiModel {
    fn name(&self) -> &str {
        "rig-openai"
    }
    async fn generate(
        &self,
        system: &str,
        question: &str,
        context: &ContextPack,
    ) -> Result<String, AppError> {
        let client = openai::Client::from_env().map_err(|_| AppError::Provider)?;
        let model = client.completion_model(&self.model);
        let agent = AgentBuilder::new(model)
            .preamble(system)
            .max_tokens(2048)
            .default_max_turns(1)
            .record_content_telemetry(false)
            .build();
        let payload = serde_json::json!({"untrustedUserQuestion":question,"untrustedEvidenceContext":context});
        // Exactly one model step in this workflow. No transparent fallback or hidden retry loop.
        agent
            .prompt(payload.to_string())
            .await
            .map_err(|_| AppError::Provider)
    }
}
