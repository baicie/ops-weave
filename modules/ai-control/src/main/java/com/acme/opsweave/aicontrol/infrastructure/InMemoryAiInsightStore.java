package com.acme.opsweave.aicontrol.infrastructure;

import com.acme.opsweave.aicontrol.api.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.incident.api.IncidentStore;
import com.acme.opsweave.incident.domain.IncidentVisibility;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.aicontrol.domain.ToolFailure.Code.*;

/** Explicit development adapter; shares the in-memory Incident/Tool adapters' monitor locks. */
public final class InMemoryAiInsightStore implements AiInsightStore {
    private final IncidentStore incidents;
    private final ToolReadStore tools;
    private final Clock clock;
    private final Map<Key,AiInsight> values = new HashMap<>();
    public InMemoryAiInsightStore(IncidentStore incidents, ToolReadStore tools, Clock clock) { this.incidents = incidents; this.tools = tools; this.clock = clock; }
    public synchronized Optional<AiInsight> find(TenantId tenant, UUID id) { return Optional.ofNullable(values.get(new Key(tenant, id))); }
    public synchronized AiInsight save(AiInsight value) {
        var key = new Key(value.tenantId(), value.input().runId()); var old = values.get(key);
        if (old != null) { if (!old.subjectId().equals(value.subjectId()) || !old.requestDigest().equals(value.requestDigest())) throw new ToolFailure(INPUT_CHANGED); return old; }
        synchronized (incidents) { synchronized (tools) {
            var s = tools.session(value.tenantId(), value.subjectId(), value.input().sessionId()).orElseThrow(() -> new ToolFailure(NOT_FOUND));
            if (!clock.instant().isBefore(s.deadlineAt())) throw new ToolFailure(EXPIRED); value.checkLive(clock.instant());
            var incident = incidents.find(value.tenantId(), value.incidentId(), new IncidentVisibility(true, Set.of(), true, Set.of())).orElseThrow(() -> new ToolFailure(NOT_FOUND));
            if (incident.incident().version() != value.incidentVersion() || !incident.entityIds().equals(value.entityIds()) || !s.incidentId().equals(value.incidentId()) || s.incidentVersion() != value.incidentVersion()
                || !s.entityIds().equals(value.entityIds()) || !s.window().equals(value.window())) throw new ToolFailure(INPUT_CHANGED);
            if (s.usedCalls() != 4 || !tools.rechecked(value.tenantId(), value.subjectId(), s.id(), Set.copyOf(value.input().evidenceIds()))) throw new ToolFailure(INVALID_REQUEST);
            if (values.values().stream().anyMatch(v -> v.tenantId().equals(value.tenantId()) && v.input().sessionId().equals(s.id()))) throw new ToolFailure(INPUT_CHANGED);
            values.put(key, value); return value;
        } }
    }
    private record Key(TenantId tenant, UUID id) {}
}
