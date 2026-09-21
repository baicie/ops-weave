//! Synthetic fixture only. Never used as a fallback for an unavailable real datasource.
use crate::{
    domain::*,
    error::AppError,
    policies::{authorize, CallBudget},
    ports::PlatformReadPort,
};
use async_trait::async_trait;
use chrono::{DateTime, Duration, Utc};
pub struct FixturePlatform;
fn evidence(
    p: &Principal,
    r: &DiagnoseRequest,
    kind: &str,
    summary: &str,
    now: DateTime<Utc>,
) -> Evidence {
    Evidence {
        id: format!("fixture-{}-{}", r.incident_id, kind),
        tenant_id: p.tenant_id.clone(),
        incident_id: r.incident_id.clone(),
        kind: kind.into(),
        summary: summary.into(),
        observed_at: r.time_range.to,
        available_at: r.time_range.to,
        expires_at: now + Duration::hours(1),
        source_ref: format!("fixture://{}/{}", r.incident_id, kind),
        trust: "untrusted_data".into(),
    }
}
#[async_trait]
impl PlatformReadPort for FixturePlatform {
    async fn incident(
        &self,
        p: &Principal,
        r: &DiagnoseRequest,
        b: &CallBudget,
        now: DateTime<Utc>,
    ) -> Result<Evidence, AppError> {
        authorize(p, &r.incident_id, "incident.read")?;
        b.consume()?;
        Ok(evidence(p,r,"incident","Synthetic fixture: order-service has an active latency incident. This is not production data.",now))
    }
    async fn metric_summary(
        &self,
        p: &Principal,
        r: &DiagnoseRequest,
        b: &CallBudget,
        now: DateTime<Utc>,
    ) -> Result<Evidence, AppError> {
        authorize(p, &r.incident_id, "metric.read")?;
        b.consume()?;
        Ok(evidence(p,r,"metric","Synthetic fixture: request latency p95 increased from 180 ms to 850 ms. A latency increase alone does not establish a database root cause.",now))
    }
}
