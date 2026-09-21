use crate::{domain::InsightDraft, error::AppError};
use serde::Deserialize;
use serde_json::Value;
use std::{fs, path::Path};

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SkillSpec {
    pub id: String,
    pub version: String,
    pub execution_template: String,
    pub prompt: String,
    pub output_schema: String,
    pub allowed_tools: Vec<String>,
    pub side_effects: String,
    pub max_tool_calls: usize,
    pub max_evidence: usize,
    pub max_context_bytes: usize,
    pub deadline_ms: u64,
}
pub struct LoadedSkill {
    pub spec: SkillSpec,
    pub prompt: String,
    pub output: jsonschema::Validator,
    canonical_output: jsonschema::Validator,
    pub digest: String,
}
fn read_file(base: &Path, name: &str) -> Result<String, AppError> {
    if name.is_empty() || name.contains('/') || name.contains('\\') || name.contains("..") {
        return Err(AppError::Configuration(
            "Skill paths must be plain filenames".into(),
        ));
    }
    let root = base
        .canonicalize()
        .map_err(|_| AppError::Configuration("Skill directory missing".into()))?;
    let file = root
        .join(name)
        .canonicalize()
        .map_err(|_| AppError::Configuration("Skill file missing".into()))?;
    if !file.starts_with(&root) {
        return Err(AppError::Configuration(
            "Skill symlink escapes package".into(),
        ));
    }
    if fs::metadata(&file)
        .map_err(|_| AppError::Configuration("Cannot inspect skill file".into()))?
        .len()
        > 65536
    {
        return Err(AppError::Configuration("Skill file too large".into()));
    }
    fs::read_to_string(file).map_err(|_| AppError::Configuration("Cannot read skill file".into()))
}
fn local_schema(value: &Value) -> bool {
    match value {
        Value::Object(m) => m.iter().all(|(k, v)| {
            if k == "$ref" || k == "$dynamicRef" {
                v.as_str().is_some_and(|s| s.starts_with('#'))
            } else {
                local_schema(v)
            }
        }),
        Value::Array(a) => a.iter().all(local_schema),
        _ => true,
    }
}
impl LoadedSkill {
    pub fn load(base: &Path) -> Result<Self, AppError> {
        let manifest = read_file(base, "skill.json")?;
        let spec: SkillSpec = serde_json::from_str(&manifest)
            .map_err(|_| AppError::Configuration("Invalid skill manifest".into()))?;
        if spec.version.trim().is_empty()
            || spec.version.len() > 64
            || spec.id != "incident.diagnose"
            || spec.execution_template != "readonly-incident-diagnosis-v1"
            || spec.side_effects != "forbidden"
            || !(2..=8).contains(&spec.max_tool_calls)
            || !(1..=32).contains(&spec.max_evidence)
            || !(1024..=65536).contains(&spec.max_context_bytes)
            || !(1000..=60000).contains(&spec.deadline_ms)
        {
            return Err(AppError::Configuration(
                "Skill exceeds supported template or policy".into(),
            ));
        }
        let required = ["incident.get@1.0.0", "metric.summary@1.0.0"];
        if spec.allowed_tools.len() != required.len()
            || required
                .iter()
                .any(|t| !spec.allowed_tools.iter().any(|x| x == t))
        {
            return Err(AppError::Configuration(
                "Template requires exactly the supported read tools".into(),
            ));
        }
        let prompt = read_file(base, &spec.prompt)?;
        let raw_schema = read_file(base, &spec.output_schema)?;
        let schema: Value = serde_json::from_str(&raw_schema)
            .map_err(|_| AppError::Configuration("Invalid schema JSON".into()))?;
        if !local_schema(&schema) {
            return Err(AppError::Configuration(
                "Remote schema references are disabled".into(),
            ));
        }
        let output = jsonschema::draft202012::options()
            .should_validate_formats(true)
            .build(&schema)
            .map_err(|_| AppError::Configuration("Invalid output schema".into()))?;
        // Content hash pins exactly what this process loaded, not just a mutable version string.
        let digest = crate::adapters::digest::sha256_parts(&[
            manifest.as_bytes(),
            prompt.as_bytes(),
            raw_schema.as_bytes(),
        ]);
        let canonical: Value = serde_json::from_str(include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../contracts/schemas/v1/insight-draft.schema.json"
        )))
        .map_err(|_| AppError::Configuration("Invalid built-in output schema".into()))?;
        let canonical_output = jsonschema::draft202012::options()
            .should_validate_formats(true)
            .build(&canonical)
            .map_err(|_| AppError::Configuration("Cannot compile built-in schema".into()))?;
        Ok(Self {
            spec,
            prompt,
            output,
            canonical_output,
            digest,
        })
    }
    pub fn parse_output(&self, text: &str) -> Result<InsightDraft, AppError> {
        if text.len() > 32768 {
            return Err(AppError::InvalidOutput);
        }
        let value: Value = serde_json::from_str(text).map_err(|_| AppError::InvalidOutput)?;
        if !self.output.is_valid(&value) || !self.canonical_output.is_valid(&value) {
            return Err(AppError::InvalidOutput);
        }
        serde_json::from_value(value).map_err(|_| AppError::InvalidOutput)
    }
}
