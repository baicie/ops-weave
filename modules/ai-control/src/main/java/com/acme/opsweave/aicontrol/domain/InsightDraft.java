package com.acme.opsweave.aicontrol.domain;

import java.util.*;

/** Canonical InsightDraft v1; source/model prose never grants permissions or executable actions. */
public record InsightDraft(String summary, List<Finding> findings, List<String> missingData, List<String> limitations) {
    public record Finding(String kind, String statement, List<UUID> evidenceRefs) {
        public Finding {
            if (!Set.of("observation", "hypothesis").contains(kind)) invalid();
            text(statement, 1, 2000); evidenceRefs = List.copyOf(evidenceRefs);
            if (evidenceRefs.isEmpty() || evidenceRefs.size() > 16 || new HashSet<>(evidenceRefs).size() != evidenceRefs.size()) invalid();
        }
    }
    public InsightDraft {
        text(summary, 1, 4000); findings = List.copyOf(findings); missingData = List.copyOf(missingData); limitations = List.copyOf(limitations);
        if (findings.size() > 12 || missingData.size() > 32 || limitations.size() > 16) invalid();
        missingData.forEach(v -> text(v, 0, 500)); limitations.forEach(v -> text(v, 0, 1000));
    }
    public void references(Set<UUID> permitted) {
        if (findings.stream().anyMatch(f -> !permitted.containsAll(f.evidenceRefs()))) invalid();
    }
    public InsightDraft protect(List<String> warnings, boolean mock) {
        var gaps = new LinkedHashSet<>(warnings); for (String gap : missingData) if (gaps.size() < 32) gaps.add(gap);
        var limits = new ArrayList<String>();
        limits.add("Reference integrity was checked; this does not establish factual truth, evidence support or causality.");
        limits.add("Current knowledge only; the sampling window is not a historical knowledge cutoff. Missing samples are not zero.");
        if (mock) limits.add("Deterministic mock output; no model inference was performed.");
        for (String limitation : limitations) if (limits.size() < 16 && !limits.contains(limitation)) limits.add(limitation);
        return new InsightDraft(summary, findings, List.copyOf(gaps), limits);
    }
    static void text(String text, int min, int max) {
        if (text == null || text.codePointCount(0, text.length()) < min || text.codePointCount(0, text.length()) > max || (min > 0 && text.isBlank())) invalid();
    }
    static void invalid() { throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST); }
}
