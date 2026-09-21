use crate::{
    context,
    domain::*,
    error::AppError,
    policies::{authorize, validate_request, CallBudget},
    ports::{ModelPort, PlatformReadPort},
    skills::LoadedSkill,
};
use chrono::Utc;
use std::sync::Arc;
use uuid::Uuid;

pub async fn diagnose(
    p: &Principal,
    r: &DiagnoseRequest,
    skill: &LoadedSkill,
    platform: Arc<dyn PlatformReadPort>,
    model: Arc<dyn ModelPort>,
) -> Result<RunResult, AppError> {
    validate_request(r)?;
    authorize(p, &r.incident_id, "ai.diagnose")?;
    authorize(p, &r.incident_id, "incident.read")?;
    authorize(p, &r.incident_id, "metric.read")?;
    authorize(p, &r.incident_id, "evidence.read")?;
    let budget = CallBudget::new(skill.spec.max_tool_calls);
    let now = Utc::now();
    let (incident, metric) = tokio::try_join!(
        platform.incident(p, r, &budget, now),
        platform.metric_summary(p, r, &budget, now)
    )?;
    let run_id = Uuid::new_v4().to_string();
    let mut pack = context::build(
        p,
        r,
        run_id.clone(),
        vec![incident, metric],
        now,
        skill.spec.max_evidence,
    )?;
    for kind in ["log", "change", "topology", "trace"] {
        pack.missing.push(MissingData {
            kind: kind.into(),
            reason: "not_implemented_in_demo".into(),
        });
    }
    if serde_json::to_vec(&pack)
        .map_err(|_| AppError::InvalidOutput)?
        .len()
        > skill.spec.max_context_bytes
    {
        return Err(AppError::Invalid("Context byte budget exceeded".into()));
    }
    let raw = model.generate(&skill.prompt, &r.question, &pack).await?;
    let mut insight = skill.parse_output(&raw)?;
    // Missing context is server-owned; a model cannot erase the disclosure.
    let mut required_gaps: Vec<String> = pack
        .missing
        .iter()
        .map(|gap| format!("{}: {}", gap.kind, gap.reason))
        .collect();
    required_gaps.sort();
    required_gaps.dedup();
    required_gaps.truncate(32);
    for gap in std::mem::take(&mut insight.missing_data) {
        if required_gaps.len() < 32 && !required_gaps.contains(&gap) {
            required_gaps.push(gap);
        }
    }
    insight.missing_data = required_gaps;
    insight.limitations.insert(0,"Evidence references were checked; this does not verify factual truth or causality. Data is synthetic fixture data.".into());
    insight.limitations.truncate(16);
    // Revalidate after adding server-owned disclosures; packages cannot weaken the canonical schema.
    let normalized = serde_json::to_string(&insight).map_err(|_| AppError::InvalidOutput)?;
    insight = skill.parse_output(&normalized)?;
    context::validate_insight(&insight, &pack, Utc::now())?;
    Ok(RunResult {
        schema_version: "1.0".into(),
        run_id,
        tenant_id: p.tenant_id.clone(),
        incident_id: r.incident_id.clone(),
        skill_id: skill.spec.id.clone(),
        skill_version: skill.spec.version.clone(),
        skill_digest: skill.digest.clone(),
        status: "succeeded".into(),
        data_mode: "synthetic-fixture".into(),
        model_provider: model.name().into(),
        verification: "reference_integrity_only".into(),
        completed_at: Utc::now(),
        context: pack,
        insight,
    })
}
