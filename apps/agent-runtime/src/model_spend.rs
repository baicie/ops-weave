//! Provider-independent request preparation and platform-issued spend admission.
use crate::{domain::CurrentContext, error::AppError};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const MAX_INPUT_BYTES: usize = 73_728;
pub const MAX_INPUT_TOKENS: u64 = 81_920;
pub const MAX_OUTPUT_TOKENS: u64 = 2_048;

// Deliberately no Debug: contains untrusted question/context and the pinned prompt.
pub struct ModelInput {
    pub system: String,
    pub payload: String,
    pub digest: String,
    pub bytes: usize,
}
impl ModelInput {
    pub fn current(
        system: &str,
        question: &str,
        context: &CurrentContext,
    ) -> Result<Self, AppError> {
        let schema: serde_json::Value = serde_json::from_str(include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../contracts/schemas/v1/insight-draft.schema.json"
        )))
        .map_err(|_| AppError::InvalidOutput)?;
        let payload = serde_json::json!({"untrustedUserQuestion":question,"untrustedEvidenceContext":context,"requiredOutputSchema":schema}).to_string();
        let bytes = system.len() + payload.len();
        if bytes > MAX_INPUT_BYTES {
            return Err(AppError::Invalid("Model input byte budget exceeded".into()));
        }
        let mut digest = Sha256::new();
        for data in [system.as_bytes(), payload.as_bytes()] {
            digest.update((data.len() as u64).to_be_bytes());
            digest.update(data);
        }
        Ok(Self {
            system: system.into(),
            payload,
            digest: format!("sha256:{:x}", digest.finalize()),
            bytes,
        })
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ModelUsage {
    pub input_tokens: u64,
    pub output_tokens: u64,
    pub cached_input_tokens: u64,
    pub source: String,
}
impl ModelUsage {
    pub fn mock() -> Self {
        Self {
            input_tokens: 0,
            output_tokens: 0,
            cached_input_tokens: 0,
            source: "mock-no-call".into(),
        }
    }
    pub fn validate(&self, provider: &str) -> Result<(), AppError> {
        let valid = match provider {
            "mock-deterministic" => *self == Self::mock(),
            "rig-openai" => {
                self.source == "provider-reported"
                    && self.input_tokens > 0
                    && self.input_tokens <= MAX_INPUT_TOKENS
                    && self.output_tokens <= MAX_OUTPUT_TOKENS
                    && self.cached_input_tokens <= self.input_tokens
            }
            _ => false,
        };
        if valid {
            Ok(())
        } else {
            Err(AppError::InvalidOutput)
        }
    }
}
pub struct ModelOutput {
    pub text: String,
    pub usage: ModelUsage,
}
#[derive(Clone)]
pub struct ModelPermit {
    pub run_id: String,
    pub session_id: String,
    pub provider: String,
    pub model: String,
    pub input_digest: String,
    pub input_bytes: usize,
    pub deadline_at: DateTime<Utc>,
    pub record: serde_json::Value,
}
impl ModelPermit {
    pub fn check(
        &self,
        run: &str,
        session: &str,
        provider: &str,
        model: &str,
        input: &ModelInput,
    ) -> Result<(), AppError> {
        if self.run_id != run
            || self.session_id != session
            || self.provider != provider
            || self.model != model
            || self.input_digest != input.digest
            || self.input_bytes != input.bytes
            || self.deadline_at <= Utc::now()
        {
            return Err(AppError::Platform);
        }
        Ok(())
    }
}
