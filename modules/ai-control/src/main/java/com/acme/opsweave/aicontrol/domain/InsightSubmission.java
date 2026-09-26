package com.acme.opsweave.aicontrol.domain;

import java.time.Instant;
import java.util.*;

/** Authenticated Runtime submission; tenant, subject and resource scope are deliberately absent. */
public record InsightSubmission(UUID runId, UUID sessionId, String question, Instant asOf, Instant builtAt, Instant completedAt,
        SkillRef skill, ModelRef model, List<UUID> evidenceIds, InsightDraft insight) {
    public record SkillRef(String id, String version, String digest) {
        public SkillRef { if (!"incident.diagnose".equals(id) || !"2.0.0".equals(version) || digest == null || !digest.matches("sha256:[0-9a-f]{64}")) InsightDraft.invalid(); }
    }
    public record ModelRef(String provider, String name) {
        public ModelRef { if (!Set.of("mock-deterministic", "rig-openai").contains(provider)) InsightDraft.invalid(); InsightDraft.text(name, 1, 128); }
    }
    public InsightSubmission {
        Objects.requireNonNull(runId); Objects.requireNonNull(sessionId); Objects.requireNonNull(asOf); Objects.requireNonNull(builtAt); Objects.requireNonNull(completedAt);
        Objects.requireNonNull(skill); Objects.requireNonNull(model); Objects.requireNonNull(insight); InsightDraft.text(question, 1, 2000);
        evidenceIds = List.copyOf(evidenceIds);
        if (evidenceIds.size() != 2 || new HashSet<>(evidenceIds).size() != 2 || asOf.isAfter(builtAt) || builtAt.isAfter(completedAt)) InsightDraft.invalid();
        insight.references(Set.copyOf(evidenceIds));
    }
    public InsightSubmission withInsight(InsightDraft value) { return new InsightSubmission(runId, sessionId, question, asOf, builtAt, completedAt, skill, model, evidenceIds, value); }
}
