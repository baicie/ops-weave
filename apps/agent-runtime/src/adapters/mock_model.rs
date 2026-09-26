use crate::{domain::*, error::AppError, ports::ModelPort};
use async_trait::async_trait;
pub struct MockModel;
#[async_trait]
impl ModelPort for MockModel {
    fn name(&self) -> &str {
        "mock-deterministic"
    }
    async fn generate(&self, _: &str, _: &str, c: &ContextPack) -> Result<String, AppError> {
        let findings = c
            .evidence
            .iter()
            .map(|e| Finding {
                kind: FindingKind::Observation,
                statement: e.summary.clone(),
                evidence_refs: vec![e.id.clone()],
            })
            .collect();
        serde_json::to_string(&InsightDraft {
            summary: "演示数据记录了订单服务延迟升高；现有证据不足以确认根因。".into(),
            findings,
            missing_data: vec!["未接入真实日志、变更、调用链和依赖拓扑".into()],
            limitations: vec!["这是确定性 Mock，不是大模型推理结果，也不执行任何动作。".into()],
        })
        .map_err(|_| AppError::InvalidOutput)
    }
    async fn generate_current(
        &self,
        _: &str,
        _: &str,
        c: &CurrentContext,
    ) -> Result<String, AppError> {
        let mut findings = Vec::new();
        for document in &c.evidence {
            let evidence: Evidence = serde_json::from_value(document["evidence"].clone())
                .map_err(|_| AppError::InvalidOutput)?;
            findings.push(Finding {
                kind: FindingKind::Observation,
                statement: evidence.summary,
                evidence_refs: vec![evidence.id],
            });
        }
        serde_json::to_string(&InsightDraft {
            summary: "已汇总当前 Incident 与指标证据；现有资料不足以确认根因。".into(),
            findings,
            missing_data: c.missing_data.clone(),
            limitations: vec!["这是确定性 Mock 摘要，未调用模型，也不执行动作。".into()],
        })
        .map_err(|_| AppError::InvalidOutput)
    }
}
