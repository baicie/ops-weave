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
    async fn generate(
        &self,
        system_prompt: &str,
        question: &str,
        context: &ContextPack,
    ) -> Result<String, AppError>;
}
