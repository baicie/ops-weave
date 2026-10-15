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

/// The Runtime's own durable internal state (M5 5d): steps, checkpoints, evidence rechecks, the
/// attempt counter of observed dispatches and the delivery outbox. Implementations must survive a
/// process restart (the journal adapter replays its records on open) and must never hand back
/// invented progress; Disabled is explicit.
#[async_trait]
pub trait RuntimeStateStore: Send + Sync {
    async fn record_step(&self, _step: &crate::runtime_state::StepRun) -> Result<(), AppError> {
        Err(AppError::Disabled)
    }
    async fn save_checkpoint(
        &self,
        _checkpoint: &crate::runtime_state::Checkpoint,
    ) -> Result<(), AppError> {
        Err(AppError::Disabled)
    }
    async fn record_recheck(
        &self,
        _recheck: &crate::runtime_state::EvidenceRecheck,
    ) -> Result<(), AppError> {
        Err(AppError::Disabled)
    }
    /// Open (or continue) one run's internal state with a new dispatch attempt. `attempt` is the
    /// count of dispatches the Runtime itself observed for this run.
    async fn open_run(
        &self,
        _run_id: &str,
        _tenant_id: &str,
        _at: chrono::DateTime<chrono::Utc>,
    ) -> Result<crate::runtime_state::RuntimeState, AppError> {
        Err(AppError::Disabled)
    }
    /// Move the internal status. A terminal internal status never moves again (explicit conflict,
    /// never a silent no-op) and the store keeps the closed transition table of `AirunStatus`.
    async fn set_status(
        &self,
        _run_id: &str,
        _status: AirunStatus,
        _at: chrono::DateTime<chrono::Utc>,
    ) -> Result<(), AppError> {
        Err(AppError::Disabled)
    }
    async fn state(
        &self,
        _run_id: &str,
    ) -> Result<Option<crate::runtime_state::RuntimeState>, AppError> {
        Err(AppError::Disabled)
    }
    async fn enqueue(&self, _entry: &crate::runtime_state::OutboxEntry) -> Result<(), AppError> {
        Err(AppError::Disabled)
    }
    async fn outbox(&self) -> Result<Vec<crate::runtime_state::OutboxEntry>, AppError> {
        Err(AppError::Disabled)
    }
    async fn update_delivery(
        &self,
        _request_id: &str,
        _entry: &crate::runtime_state::OutboxEntry,
    ) -> Result<(), AppError> {
        Err(AppError::Disabled)
    }
}

/// The platform run registry as the Runtime reads it, through the controlled platform API only.
/// Disabled is explicit: no fixture may pretend the platform was consulted.
#[async_trait]
pub trait PlatformRunViewPort: Send + Sync {
    async fn run_view(
        &self,
        _run_id: &str,
    ) -> Result<Option<crate::runtime_state::PlatformRunView>, AppError> {
        Err(AppError::Disabled)
    }
}

/// One delivery of a runtime state report to the platform. At-least-once: the caller retries after
/// an `Unknown` outcome and the platform deduplicates on the report's idempotency key. Exactly-once
/// is never claimed.
#[async_trait]
pub trait StateReportDelivery: Send + Sync {
    async fn deliver(
        &self,
        _report: &crate::runtime_state::RuntimeStateReport,
    ) -> Result<crate::runtime_state::DeliveryState, AppError> {
        Err(AppError::Disabled)
    }
}
