use crate::{domain::*, error::AppError, policies::CallBudget};
use async_trait::async_trait;
use chrono::{DateTime, Utc};

#[async_trait]
pub trait PlatformReadPort: Send + Sync {
    async fn incident(
        &self,
        p: &Principal,
        r: &DiagnoseRequest,
        budget: &CallBudget,
        now: DateTime<Utc>,
    ) -> Result<Evidence, AppError>;
    async fn metric_summary(
        &self,
        p: &Principal,
        r: &DiagnoseRequest,
        budget: &CallBudget,
        now: DateTime<Utc>,
    ) -> Result<Evidence, AppError>;
}
#[async_trait]
pub trait ModelPort: Send + Sync {
    fn name(&self) -> &str;
    async fn generate_metered(
        &self,
        input: &crate::model_spend::ModelInput,
        context: &CurrentContext,
    ) -> Result<crate::model_spend::ModelOutput, AppError> {
        if self.name() != "mock-deterministic" {
            return Err(AppError::Disabled);
        }
        // Legacy mock implementations can participate without inventing provider tokens.
        let question = serde_json::from_str::<serde_json::Value>(&input.payload)
            .map_err(|_| AppError::InvalidOutput)?;
        let text = self
            .generate_current(
                &input.system,
                question["untrustedUserQuestion"]
                    .as_str()
                    .ok_or(AppError::InvalidOutput)?,
                context,
            )
            .await?;
        Ok(crate::model_spend::ModelOutput {
            text,
            usage: crate::model_spend::ModelUsage::mock(),
        })
    }
    async fn generate(
        &self,
        system_prompt: &str,
        question: &str,
        context: &ContextPack,
    ) -> Result<String, AppError>;
    async fn generate_current(
        &self,
        _system_prompt: &str,
        _question: &str,
        _context: &CurrentContext,
    ) -> Result<String, AppError> {
        Err(AppError::Disabled)
    }
}

#[async_trait]
pub trait CurrentReadSessionPort: Send + Sync {
    fn principal(&self) -> Principal;
    fn session_id(&self) -> &str;
    fn window(&self) -> &TimeRange;
    async fn reserve_model(
        &self,
        _run: &str,
        _input: &crate::model_spend::ModelInput,
    ) -> Result<crate::model_spend::ModelPermit, AppError> {
        Err(AppError::Disabled)
    }
    async fn report_model(
        &self,
        _permit: &crate::model_spend::ModelPermit,
        _usage: &crate::model_spend::ModelUsage,
    ) -> Result<(), AppError> {
        Err(AppError::Disabled)
    }
    async fn incident(&self) -> Result<PlatformSnapshot, AppError>;
    async fn metric(&self) -> Result<PlatformSnapshot, AppError>;
    async fn recheck(&self, snapshot: &PlatformSnapshot) -> Result<(), AppError>;
    async fn save(&self, submission: &InsightSubmission) -> Result<SavedInsight, AppError>;
}

/// Durable run registry port (ADR-104). 5a keeps persistence on the platform side and leaves this
/// port disabled; it is the seam where the Runtime owns its own state in 5b/5c. Disabled is
/// explicit: no fixture store may pretend a run was persisted or advanced.
#[async_trait]
pub trait RunStore: Send + Sync {
    async fn submit(&self, _run: &Airun) -> Result<Airun, AppError> {
        Err(AppError::Disabled)
    }
    async fn get(&self, _run_id: &str) -> Result<Option<Airun>, AppError> {
        Err(AppError::Disabled)
    }
    async fn advance(
        &self,
        _run_id: &str,
        _expected_version: u64,
        _next: AirunStatus,
    ) -> Result<Option<Airun>, AppError> {
        Err(AppError::Disabled)
    }
}
