//! Business contracts. No framework, SQL or model-SDK types are allowed here.
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

#[derive(Clone, Debug)]
pub struct Principal {
    pub tenant_id: String,
    pub user_id: String,
    pub permissions: BTreeSet<String>,
    pub allowed_incidents: BTreeSet<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TimeRange {
    pub from: DateTime<Utc>,
    pub to: DateTime<Utc>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DiagnoseRequest {
    pub incident_id: String,
    pub question: String,
    pub time_range: TimeRange,
    pub as_of: DateTime<Utc>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Evidence {
    pub id: String,
    pub tenant_id: String,
    pub incident_id: String,
    pub kind: String,
    pub summary: String,
    pub observed_at: DateTime<Utc>,
    pub available_at: DateTime<Utc>,
    pub expires_at: DateTime<Utc>,
    pub source_ref: String,
    pub trust: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MissingData {
    pub kind: String,
    pub reason: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ContextPack {
    pub schema_version: String,
    pub run_id: String,
    pub tenant_id: String,
    pub incident_id: String,
    pub time_range: TimeRange,
    pub as_of: DateTime<Utc>,
    pub built_at: DateTime<Utc>,
    pub evidence: Vec<Evidence>,
    pub missing: Vec<MissingData>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FindingKind {
    Observation,
    Hypothesis,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Finding {
    pub kind: FindingKind,
    pub statement: String,
    pub evidence_refs: Vec<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightDraft {
    pub summary: String,
    pub findings: Vec<Finding>,
    pub missing_data: Vec<String>,
    pub limitations: Vec<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RunResult {
    pub schema_version: String,
    pub run_id: String,
    pub tenant_id: String,
    pub incident_id: String,
    pub skill_id: String,
    pub skill_version: String,
    pub skill_digest: String,
    pub status: String,
    pub data_mode: String,
    pub model_provider: String,
    pub verification: String,
    pub completed_at: DateTime<Utc>,
    pub context: ContextPack,
    pub insight: InsightDraft,
}

/// Persist this separately from model conversation history in a durable implementation.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RunState {
    Queued,
    Running,
    WaitingApproval,
    Succeeded,
    Failed,
    Cancelled,
}
impl RunState {
    pub fn can_transition_to(&self, next: &Self) -> bool {
        use RunState::*;
        matches!(
            (self, next),
            (Queued, Running)
                | (Queued, Cancelled)
                | (Running, WaitingApproval)
                | (Running, Succeeded)
                | (Running, Failed)
                | (Running, Cancelled)
                | (WaitingApproval, Queued)
                | (WaitingApproval, Cancelled)
        )
    }
}
