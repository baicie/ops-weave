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

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CurrentDiagnoseRequest {
    pub run_id: String,
    pub incident_id: String,
    pub question: String,
    pub time_range: TimeRange,
    pub knowledge_mode: String,
}
/// Canonical current-knowledge context. Documents retain structured data and its actual capture times.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CurrentContext {
    pub schema_version: String,
    pub knowledge_mode: String,
    pub run_id: String,
    pub tenant_id: String,
    pub incident_id: String,
    pub session_id: String,
    pub time_range: TimeRange,
    pub as_of: DateTime<Utc>,
    pub built_at: DateTime<Utc>,
    pub evidence: Vec<serde_json::Value>,
    pub missing_data: Vec<String>,
}
pub struct PlatformSnapshot {
    pub(crate) document: serde_json::Value,
    pub(crate) evidence: Evidence,
}
impl PlatformSnapshot {
    /// A snapshot obtained through a read port only. Callers never fabricate one from model text.
    pub fn new(document: serde_json::Value, evidence: Evidence) -> Self {
        Self { document, evidence }
    }
    pub fn document(&self) -> &serde_json::Value {
        &self.document
    }
    pub fn evidence(&self) -> &Evidence {
        &self.evidence
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightSkillRef {
    pub id: String,
    pub version: String,
    pub digest: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightModelRef {
    pub provider: String,
    pub name: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightSubmission {
    pub run_id: String,
    pub session_id: String,
    pub question: String,
    pub as_of: DateTime<Utc>,
    pub built_at: DateTime<Utc>,
    pub completed_at: DateTime<Utc>,
    pub skill: InsightSkillRef,
    pub model: InsightModelRef,
    pub evidence_ids: Vec<String>,
    pub insight: InsightDraft,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SavedInsight {
    pub storage: String,
    pub record: serde_json::Value,
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

/// Durable AIRun state machine of ADR-104. `RunState` above is legacy in-memory state and is not a
/// persistent execution engine; this mirror keeps the Runtime on the same closed transition table as
/// the platform run registry until the Runtime owns its own durable state in 5b/5c. The wire names
/// are the platform's own state vocabulary (`PENDING`…`CANCELLED`), so both stores spell one state
/// the same way.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AirunStatus {
    Pending,
    Running,
    WaitingTool,
    Succeeded,
    Failed,
    Cancelled,
}
impl AirunStatus {
    pub fn terminal(&self) -> bool {
        matches!(self, Self::Succeeded | Self::Failed | Self::Cancelled)
    }
    pub fn can_transition_to(&self, next: &Self) -> bool {
        use AirunStatus::*;
        matches!(
            (self, next),
            (Pending, Running)
                | (Pending, Failed)
                | (Pending, Cancelled)
                | (Running, WaitingTool)
                | (Running, Succeeded)
                | (Running, Failed)
                | (Running, Cancelled)
                | (WaitingTool, Running)
                | (WaitingTool, Failed)
                | (WaitingTool, Cancelled)
        )
    }
}

/// One run registry entry as the platform hands it to the Runtime. Submitting a run executes
/// nothing; only the future executor seam moves its state, and only with a compare-and-set.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Airun {
    pub run_id: String,
    pub tenant_id: String,
    pub incident_id: String,
    pub request_id: String,
    pub command_digest: String,
    pub status: AirunStatus,
    pub status_version: u64,
}
impl Airun {
    /// Pure transition; the store applies it with a compare-and-set on `status_version`.
    pub fn transition_to(&self, next: AirunStatus) -> Option<Self> {
        if self.status.terminal() || !self.status.can_transition_to(&next) {
            return None;
        }
        Some(Self {
            run_id: self.run_id.clone(),
            tenant_id: self.tenant_id.clone(),
            incident_id: self.incident_id.clone(),
            request_id: self.request_id.clone(),
            command_digest: self.command_digest.clone(),
            status: next,
            status_version: self.status_version + 1,
        })
    }
}
