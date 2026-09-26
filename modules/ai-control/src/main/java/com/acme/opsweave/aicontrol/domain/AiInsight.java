package com.acme.opsweave.aicontrol.domain;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;

/** Platform-owned saved result. requestDigest is internal idempotency metadata, never model authority. */
public record AiInsight(TenantId tenantId, SubjectId subjectId, UUID incidentId, long incidentVersion, Set<EntityId> entityIds,
        ToolWindow window, InsightSubmission input, String requestDigest, Instant savedAt, Instant expiresAt,
        List<String> dataModes, List<String> warnings) {
    public AiInsight {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(subjectId); Objects.requireNonNull(incidentId); Objects.requireNonNull(window); Objects.requireNonNull(input);
        Objects.requireNonNull(savedAt); Objects.requireNonNull(expiresAt); entityIds = Set.copyOf(entityIds); dataModes = List.copyOf(dataModes); warnings = List.copyOf(warnings);
        if (incidentVersion < 1 || entityIds.size() > 5 || requestDigest == null || !requestDigest.matches("sha256:[0-9a-f]{64}")
            || input.completedAt().isAfter(savedAt) || !expiresAt.isAfter(savedAt) || window.to().isAfter(input.asOf())
            || warnings.isEmpty() || warnings.size() > 32 || new HashSet<>(warnings).size() != warnings.size()
            || !warnings.contains("CURRENT_KNOWLEDGE_ONLY") || warnings.stream().anyMatch(w -> !w.matches("[A-Z][A-Z0-9_]{0,63}"))
            || dataModes.isEmpty() || dataModes.size() > 3 || new HashSet<>(dataModes).size() != dataModes.size()
            || dataModes.stream().anyMatch(mode -> !Set.of("labeled-fixture", "zabbix-jsonrpc", "unknown").contains(mode))) InsightDraft.invalid();
    }
    public void checkLive(Instant now) { if (!now.isBefore(expiresAt)) throw new ToolFailure(ToolFailure.Code.EXPIRED); }
}
