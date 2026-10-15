//! Runtime-owned internal run state (M5 5d, ADR-104 §2.2/§3).
//!
//! The platform registry (`ai_control.ai_run`) keeps the run registration and its receipts; this
//! module is the Runtime's own durable view of *how far one run got internally*: the executed
//! steps, the resumable checkpoints, the attempt counter of dispatches the Runtime itself observed
//! and the evidence recheck records. It is deliberately pure: no Rig, no Axum, no SQLx and no MCP
//! types may appear here, only serde/chrono primitives and the closed domain vocabulary.
//!
//! Two honesty rules are structural, not conventional:
//!
//! * **Delivery is at-least-once, never exactly-once.** A state report leaves the Runtime through an
//!   outbox entry with an idempotency key; a transport failure leaves the outcome `Unknown` (never
//!   silently "delivered", never silently dropped), and a retry may deliver the same report twice —//!   the consumer is idempotent on the request id.
//! * **Reconciliation only discloses.** [`reconcile`] compares the platform's registry view with the
//!   Runtime's own state and returns a report of closed findings; it never repairs, never rewrites
//!   and never starts or stops execution. Divergence is data to look at, not something to patch.
use crate::domain::AirunStatus;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// The closed set of internal pipeline steps of the current-knowledge diagnosis chain.
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum StepKind {
    ContextBuild,
    ModelCall,
    EvidenceRecheck,
    InsightSave,
}
impl StepKind {
    pub fn name(&self) -> &'static str {
        match self {
            Self::ContextBuild => "CONTEXT_BUILD",
            Self::ModelCall => "MODEL_CALL",
            Self::EvidenceRecheck => "EVIDENCE_RECHECK",
            Self::InsightSave => "INSIGHT_SAVE",
        }
    }
}

/// Where one step stands. `Running` is the open step; the two others are final for that step.
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum StepStatus {
    Running,
    Succeeded,
    Failed,
}

/// One recorded step of one attempt. Steps are records of work that already happened (or is
/// happening); they never instruct anything and they never carry model text or evidence bodies.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StepRun {
    pub run_id: String,
    pub attempt: u32,
    pub step: StepKind,
    pub status: StepStatus,
    pub started_at: DateTime<Utc>,
    pub finished_at: Option<DateTime<Utc>>,
    pub reason: Option<String>,
}
impl StepRun {
    /// A valid step record or nothing: closed bounds, a finished step carries its finish time and
    /// no reason on success. An open step carries neither.
    pub fn new(
        run_id: &str,
        attempt: u32,
        step: StepKind,
        status: StepStatus,
        started_at: DateTime<Utc>,
        finished_at: Option<DateTime<Utc>>,
        reason: Option<String>,
    ) -> Option<Self> {
        if run_id.is_empty() || run_id.len() > 64 || attempt > 1_000_000 {
            return None;
        }
        match status {
            StepStatus::Running => {
                if finished_at.is_some() || reason.is_some() {
                    return None;
                }
            }
            StepStatus::Succeeded => {
                if finished_at.is_none() || reason.is_some() {
                    return None;
                }
            }
            StepStatus::Failed => {
                if finished_at.is_none()
                    || reason
                        .as_deref()
                        .is_none_or(|r| r.is_empty() || r.len() > 200)
                {
                    return None;
                }
            }
        }
        if finished_at.is_some_and(|at| at < started_at) {
            return None;
        }
        Some(Self {
            run_id: run_id.to_owned(),
            attempt,
            step,
            status,
            started_at,
            finished_at,
            reason,
        })
    }
}

/// The closed recheck verdict of one evidence item. `missing` is preserved as missing —never
/// guessed away, never back-filled.
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RecheckVerdict {
    Current,
    Expired,
    Missing,
    Unavailable,
}

/// One evidence recheck record: what the Runtime checked and when, with the capture times of the
/// evidence itself kept next to the verdict.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EvidenceRecheck {
    pub run_id: String,
    pub attempt: u32,
    pub evidence_id: String,
    pub as_of: DateTime<Utc>,
    pub observed_at: DateTime<Utc>,
    pub available_at: DateTime<Utc>,
    pub expires_at: DateTime<Utc>,
    pub verdict: RecheckVerdict,
    pub recorded_at: DateTime<Utc>,
}
impl EvidenceRecheck {
    pub fn new(
        run_id: &str,
        attempt: u32,
        evidence_id: &str,
        as_of: DateTime<Utc>,
        observed_at: DateTime<Utc>,
        available_at: DateTime<Utc>,
        expires_at: DateTime<Utc>,
        recorded_at: DateTime<Utc>,
    ) -> Option<Self> {
        if run_id.is_empty() || run_id.len() > 64 || attempt > 1_000_000 || evidence_id.is_empty() {
            return None;
        }
        if evidence_id.len() > 128 || observed_at > available_at || available_at > expires_at {
            return None;
        }
        let verdict = if observed_at > as_of || available_at > as_of {
            RecheckVerdict::Unavailable
        } else if expires_at <= as_of {
            RecheckVerdict::Expired
        } else {
            RecheckVerdict::Current
        };
        Some(Self {
            run_id: run_id.to_owned(),
            attempt,
            evidence_id: evidence_id.to_owned(),
            as_of,
            observed_at,
            available_at,
            expires_at,
            verdict,
            recorded_at,
        })
    }
}

/// One resumable checkpoint: how far the internal progress got before the next step. `seq` is per
/// run and strictly monotonic; a crash loses at most the step in flight, never the checkpoint.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Checkpoint {
    pub run_id: String,
    pub attempt: u32,
    pub seq: u64,
    pub step: StepKind,
    pub as_of: DateTime<Utc>,
    pub evidence_ids: Vec<String>,
    pub missing_data: Vec<String>,
    pub saved_at: DateTime<Utc>,
}
impl Checkpoint {
    pub fn new(
        run_id: &str,
        attempt: u32,
        seq: u64,
        step: StepKind,
        as_of: DateTime<Utc>,
        evidence_ids: Vec<String>,
        missing_data: Vec<String>,
        saved_at: DateTime<Utc>,
    ) -> Option<Self> {
        if run_id.is_empty()
            || run_id.len() > 64
            || attempt > 1_000_000
            || seq == 0
            || seq > 1_000_000_000
        {
            return None;
        }
        if evidence_ids.len() > 32 || missing_data.len() > 32 {
            return None;
        }
        if evidence_ids
            .iter()
            .any(|id| id.is_empty() || id.len() > 128)
            || missing_data.iter().any(|m| m.is_empty() || m.len() > 200)
            || saved_at < as_of
        {
            return None;
        }
        Some(Self {
            run_id: run_id.to_owned(),
            attempt,
            seq,
            step,
            as_of,
            evidence_ids,
            missing_data,
            saved_at,
        })
    }
}

/// The Runtime's internal state of one run: where its own pipeline stands, under which attempt, with
/// every recorded step, checkpoint and evidence recheck. This is Runtime-owned state —the platform
/// registry never stores it.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RuntimeState {
    pub run_id: String,
    pub tenant_id: String,
    pub attempt: u32,
    pub status: AirunStatus,
    pub steps: Vec<StepRun>,
    pub checkpoints: Vec<Checkpoint>,
    pub rechecks: Vec<EvidenceRecheck>,
    pub updated_at: DateTime<Utc>,
}
impl RuntimeState {
    /// Rebuild one run's state from its records. Structural violations are rejected, never repaired:
    /// a checkpoint sequence that is not strictly monotonic, a record of another run or an attempt
    /// above the run's attempt counter means the journal is inconsistent and must surface as such.
    pub fn assemble(
        run_id: &str,
        tenant_id: &str,
        attempt: u32,
        status: AirunStatus,
        steps: Vec<StepRun>,
        checkpoints: Vec<Checkpoint>,
        rechecks: Vec<EvidenceRecheck>,
        updated_at: DateTime<Utc>,
    ) -> Option<Self> {
        if run_id.is_empty() || tenant_id.is_empty() || attempt > 1_000_000 {
            return None;
        }
        if steps
            .iter()
            .any(|s| s.run_id != run_id || s.attempt > attempt)
            || rechecks
                .iter()
                .any(|r| r.run_id != run_id || r.attempt > attempt)
        {
            return None;
        }
        let mut last_seq = 0u64;
        for checkpoint in &checkpoints {
            if checkpoint.run_id != run_id
                || checkpoint.attempt > attempt
                || checkpoint.seq <= last_seq
            {
                return None;
            }
            last_seq = checkpoint.seq;
        }
        Some(Self {
            run_id: run_id.to_owned(),
            tenant_id: tenant_id.to_owned(),
            attempt,
            status,
            steps,
            checkpoints,
            rechecks,
            updated_at,
        })
    }

    /// The checkpoint a crashed run resumes from: the newest one, never a guessed position.
    pub fn latest_checkpoint(&self) -> Option<&Checkpoint> {
        self.checkpoints.last()
    }

    /// A closed attestation of this state: counters and status only. The platform receives a
    /// receipt projection —never the internal steps, checkpoints or evidence records themselves.
    pub fn report(
        &self,
        request_id: &str,
        reported_at: DateTime<Utc>,
    ) -> Option<RuntimeStateReport> {
        RuntimeStateReport::new(
            request_id,
            &self.run_id,
            self.attempt,
            self.status.clone(),
            self.steps.len() as u64,
            self.checkpoints.last().map(|c| c.seq).unwrap_or(0),
            self.rechecks.len() as u64,
            reported_at,
        )
    }
}

/// The at-least-once state report as it travels to the platform: idempotency key plus counters.
/// It carries no step names, no checkpoint content and no evidence body.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RuntimeStateReport {
    pub request_id: String,
    pub run_id: String,
    pub attempt: u32,
    pub status: AirunStatus,
    pub step_count: u64,
    pub checkpoint_seq: u64,
    pub evidence_recheck_count: u64,
    pub reported_at: DateTime<Utc>,
}
impl RuntimeStateReport {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        request_id: &str,
        run_id: &str,
        attempt: u32,
        status: AirunStatus,
        step_count: u64,
        checkpoint_seq: u64,
        evidence_recheck_count: u64,
        reported_at: DateTime<Utc>,
    ) -> Option<Self> {
        if request_id.is_empty()
            || request_id.len() > 64
            || run_id.is_empty()
            || run_id.len() > 64
            || attempt > 1_000_000
            || step_count > 1_000
            || checkpoint_seq > 1_000_000_000
            || evidence_recheck_count > 1_000
        {
            return None;
        }
        Some(Self {
            request_id: request_id.to_owned(),
            run_id: run_id.to_owned(),
            attempt,
            status,
            step_count,
            checkpoint_seq,
            evidence_recheck_count,
            reported_at,
        })
    }
}

/// Delivery state of one outbox entry. `Unknown` is the honest answer to "I do not know whether the
/// platform recorded it": the report is retried and may arrive twice; it is never marked delivered
/// on hope and never dropped silently.
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DeliveryState {
    Pending,
    Delivered,
    Unknown,
}

/// One outbox entry: the report to deliver, its delivery state and the number of attempts made.
/// Attempts are bounded; exceeding the bound keeps the entry as `Unknown` (disclosed), never as a
/// fake success.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OutboxEntry {
    pub report: RuntimeStateReport,
    pub state: DeliveryState,
    pub attempts: u32,
    pub updated_at: DateTime<Utc>,
}
impl OutboxEntry {
    /// The maximum delivery attempts per report; after that the entry stays `Unknown` and visible.
    pub const MAX_ATTEMPTS: u32 = 5;

    pub fn enqueue(report: RuntimeStateReport, at: DateTime<Utc>) -> Self {
        Self {
            report,
            state: DeliveryState::Pending,
            attempts: 0,
            updated_at: at,
        }
    }

    /// Record one delivery attempt and its outcome. A failure moves the entry to `Unknown`, never
    /// back to `Pending` (the attempt happened) and never to `Delivered` (nothing was confirmed).
    pub fn record_attempt(&self, delivered: bool, at: DateTime<Utc>) -> Option<Self> {
        if self.attempts >= Self::MAX_ATTEMPTS {
            return None;
        }
        Some(Self {
            report: self.report.clone(),
            state: if delivered {
                DeliveryState::Delivered
            } else {
                DeliveryState::Unknown
            },
            attempts: self.attempts + 1,
            updated_at: at,
        })
    }

    /// A retry is allowed while the report is undelivered and the attempt budget is not spent.
    pub fn may_retry(&self) -> bool {
        self.state != DeliveryState::Delivered && self.attempts < Self::MAX_ATTEMPTS
    }
}

/// The platform registry as the Runtime last saw it. It is a view, never authority: the Runtime does
/// not write it and does not trust it beyond what a reconciliation report discloses.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlatformRunView {
    pub run_id: String,
    pub status: AirunStatus,
    pub status_version: u64,
    pub attempt: u32,
    pub updated_at: DateTime<Utc>,
}

/// The closed finding set of a cross-store reconciliation. The same names are the contract's closed
/// enum (`ai-run-cross-store-report`); both sides emit subsets of this set and nothing else.
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ReconciliationFinding {
    /// The Runtime holds state for a run the platform registry does not know.
    MissingOnPlatform,
    /// No Runtime state has been delivered or recorded for a registered run.
    MissingRuntimeState,
    /// Both sides are active but disagree about the status.
    StatusMismatch,
    /// The platform is terminal while the Runtime is still working —disclosed, never patched.
    PlatformTerminalRuntimeActive,
    /// The Runtime is terminal while the platform still shows an unfinished run.
    RuntimeTerminalPlatformActive,
    /// The attempt counters disagree (for example a recovery attempt the Runtime never saw).
    AttemptMismatch,
    /// The Runtime's reported state is older than the platform's latest transition.
    StaleRuntimeReport,
    /// The Runtime has state reports that were never confirmed as delivered.
    UndeliveredStateReport,
}
impl ReconciliationFinding {
    pub fn code(&self) -> &'static str {
        match self {
            Self::MissingOnPlatform => "MISSING_ON_PLATFORM",
            Self::MissingRuntimeState => "MISSING_RUNTIME_STATE",
            Self::StatusMismatch => "STATUS_MISMATCH",
            Self::PlatformTerminalRuntimeActive => "PLATFORM_TERMINAL_RUNTIME_ACTIVE",
            Self::RuntimeTerminalPlatformActive => "RUNTIME_TERMINAL_PLATFORM_ACTIVE",
            Self::AttemptMismatch => "ATTEMPT_MISMATCH",
            Self::StaleRuntimeReport => "STALE_RUNTIME_REPORT",
            Self::UndeliveredStateReport => "UNDELIVERED_STATE_REPORT",
        }
    }
}

/// The Runtime's side of the comparison: status, attempt and the newest checkpoint it holds.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RuntimeView {
    pub status: AirunStatus,
    pub attempt: u32,
    pub checkpoint_seq: u64,
    pub step_count: u64,
    pub updated_at: DateTime<Utc>,
}

/// The cross-store reconciliation report. `repair` is a constant: a report describes divergence, it
/// never fixes it —no state is rewritten, no run is started, stopped or re-run because of it.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReconciliationReport {
    pub run_id: String,
    pub compared_at: DateTime<Utc>,
    pub platform: Option<PlatformRunView>,
    pub runtime: Option<RuntimeView>,
    pub findings: Vec<ReconciliationFinding>,
}
impl ReconciliationReport {
    /// The constant disposition of every report: disclose only.
    pub const REPAIR: &'static str = "none";

    pub fn repair(&self) -> &'static str {
        Self::REPAIR
    }

    pub fn consistent(&self) -> bool {
        self.findings.is_empty()
    }
}

/// Compare the two stores' views of one run. Pure and total: it reads both sides, returns the closed
/// finding list and mutates nothing. Terminal-vs-active divergence gets its own finding (it is not
/// hidden behind a generic status mismatch); attempt and staleness findings are independent.
pub fn reconcile(
    run_id: &str,
    platform: Option<&PlatformRunView>,
    runtime: Option<&RuntimeView>,
    undelivered_reports: u64,
    compared_at: DateTime<Utc>,
) -> Option<ReconciliationReport> {
    // A report compares two sides; with neither side present there is nothing to disclose.
    if run_id.is_empty()
        || (platform.is_none() && runtime.is_none())
        || platform.is_some_and(|p| p.run_id != run_id)
    {
        return None;
    }
    let mut findings = Vec::new();
    match (platform, runtime) {
        (None, Some(_)) => findings.push(ReconciliationFinding::MissingOnPlatform),
        (Some(_), None) => findings.push(ReconciliationFinding::MissingRuntimeState),
        (None, None) => return None,
        (Some(p), Some(r)) => {
            let platform_terminal = p.status.terminal();
            let runtime_terminal = r.status.terminal();
            if platform_terminal && !runtime_terminal {
                findings.push(ReconciliationFinding::PlatformTerminalRuntimeActive);
            } else if !platform_terminal && runtime_terminal {
                findings.push(ReconciliationFinding::RuntimeTerminalPlatformActive);
            } else if p.status != r.status {
                findings.push(ReconciliationFinding::StatusMismatch);
            }
            if p.attempt != r.attempt {
                findings.push(ReconciliationFinding::AttemptMismatch);
            }
            if r.updated_at < p.updated_at {
                findings.push(ReconciliationFinding::StaleRuntimeReport);
            }
        }
    }
    if undelivered_reports > 0 {
        findings.push(ReconciliationFinding::UndeliveredStateReport);
    }
    findings.sort();
    findings.dedup();
    Some(ReconciliationReport {
        run_id: run_id.to_owned(),
        compared_at,
        platform: platform.cloned(),
        runtime: runtime.cloned(),
        findings,
    })
}
