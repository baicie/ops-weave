use crate::{domain::*, error::AppError, policies::authorize};
use chrono::{DateTime, Utc};
use std::collections::BTreeSet;

pub fn build(
    principal: &Principal,
    request: &DiagnoseRequest,
    run_id: String,
    evidence: Vec<Evidence>,
    now: DateTime<Utc>,
    max_items: usize,
) -> Result<ContextPack, AppError> {
    authorize(principal, &request.incident_id, "evidence.read")?;
    if !(1..=32).contains(&max_items) || now < request.as_of {
        return Err(AppError::Invalid("Invalid context budget or clock".into()));
    }
    let mut seen = BTreeSet::new();
    let mut selected = Vec::new();
    let mut missing = Vec::new();
    for e in evidence {
        if e.tenant_id != principal.tenant_id || e.incident_id != request.incident_id {
            return Err(AppError::Forbidden);
        }
        if !seen.insert(e.id.clone()) || e.id.is_empty() {
            return Err(AppError::Invalid("Duplicate or empty evidence ID".into()));
        }
        if e.trust != "untrusted_data"
            || e.summary.chars().count() > 2000
            || e.expires_at <= e.available_at
        {
            return Err(AppError::Invalid("Invalid evidence metadata".into()));
        }
        let reason = if e.expires_at <= now {
            Some("expired_at_access")
        } else if e.available_at > request.as_of || e.observed_at > request.as_of {
            Some("not_available_at_as_of")
        } else if e.observed_at < request.time_range.from || e.observed_at > request.time_range.to {
            Some("outside_analysis_window")
        } else if selected.len() >= max_items {
            Some("context_budget_exceeded")
        } else {
            None
        };
        if let Some(r) = reason {
            missing.push(MissingData {
                kind: e.kind,
                reason: r.into(),
            });
        } else {
            selected.push(e);
        }
    }
    if selected.is_empty() {
        missing.push(MissingData {
            kind: "evidence".into(),
            reason: "no_usable_evidence".into(),
        });
    }
    missing.truncate(32);
    Ok(ContextPack {
        schema_version: "2.0".into(),
        run_id,
        tenant_id: principal.tenant_id.clone(),
        incident_id: request.incident_id.clone(),
        time_range: request.time_range.clone(),
        as_of: request.as_of,
        built_at: now,
        evidence: selected,
        missing,
    })
}

/// Referential validation is NOT factual truth verification or causal proof.
pub fn validate_insight(
    insight: &InsightDraft,
    context: &ContextPack,
    now: DateTime<Utc>,
) -> Result<(), AppError> {
    let allowed: BTreeSet<_> = context
        .evidence
        .iter()
        .filter(|e| e.expires_at > now)
        .map(|e| e.id.as_str())
        .collect();
    for f in &insight.findings {
        if f.evidence_refs.is_empty() {
            return Err(AppError::InvalidOutput);
        }
        let mut seen = BTreeSet::new();
        for id in &f.evidence_refs {
            if !allowed.contains(id.as_str()) || !seen.insert(id) {
                return Err(AppError::InvalidOutput);
            }
        }
    }
    if context.evidence.is_empty() && insight.missing_data.is_empty() {
        return Err(AppError::InvalidOutput);
    }
    Ok(())
}
