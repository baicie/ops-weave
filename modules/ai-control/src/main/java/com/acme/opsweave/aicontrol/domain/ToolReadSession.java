package com.acme.opsweave.aicontrol.domain;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

/** Server-owned authorization context and shared budget for a single current-knowledge read. */
public record ToolReadSession(UUID id, TenantId tenantId, SubjectId subjectId, UUID incidentId, long incidentVersion,
        Set<EntityId> entityIds, ToolWindow window, Instant createdAt, Instant deadlineAt, int usedCalls) {
    public static final int MAX_CALLS = 4;
    public static final int MAX_ENTITIES = 5;
    public static final Set<String> TOOLS = Set.of("incident.get@2.0.0", "metric.summary@2.0.0", "evidence.get@2.0.0");
    public ToolReadSession {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(subjectId); Objects.requireNonNull(incidentId);
        Objects.requireNonNull(window); Objects.requireNonNull(createdAt); Objects.requireNonNull(deadlineAt); entityIds = Set.copyOf(entityIds);
        if (incidentVersion < 1 || entityIds.size() > MAX_ENTITIES || usedCalls < 0 || usedCalls > MAX_CALLS
            || !deadlineAt.equals(createdAt.plusSeconds(60)) || window.to().isAfter(createdAt)) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
    }
    public ToolReadSession consume(Instant now) {
        if (!now.isBefore(deadlineAt)) throw new ToolFailure(ToolFailure.Code.EXPIRED);
        if (usedCalls == MAX_CALLS) throw new ToolFailure(ToolFailure.Code.BUDGET_EXHAUSTED);
        return new ToolReadSession(id, tenantId, subjectId, incidentId, incidentVersion, entityIds, window, createdAt, deadlineAt, usedCalls + 1);
    }
}
