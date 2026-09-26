package com.acme.opsweave.incident.api;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;

public interface IncidentStore {
    /** All-or-nothing page write, including problem occurrence, initial Incident, entity links and timeline. */
    ImportResult ingest(TenantId tenant, String source, String dataMode, List<ExternalProblem> problems, Instant receivedAt);
    Optional<IncidentRecord> find(TenantId tenant, UUID id, IncidentVisibility visibility);
    /** At most limit + 1 headers; scope must be applied before LIMIT. */
    List<IncidentRecord.Header> page(TenantId tenant, IncidentVisibility visibility, IncidentStatus status, UUID after, int limit);
    TransitionResult transition(TenantId tenant, UUID id, IncidentVisibility visibility, long expectedVersion, IncidentStatus target, UUID requestKey, String actor, Instant now);
    IncidentReorganization.Receipt reorganize(TenantId tenant, IncidentReorganization.Request request, IncidentVisibility visibility, String actor, Instant now);
    Optional<IncidentReorganization.Receipt> reorganization(TenantId tenant, UUID requestKey, IncidentVisibility visibility);
    List<IncidentReorganization.Receipt> reorganizations(TenantId tenant, UUID incidentId, IncidentVisibility visibility, UUID after, int limit);
    record ImportResult(int accepted, int createdIncidents, int changedIncidents, int unmappedHosts) {}
    record TransitionResult(UUID incidentId, IncidentStatus status, long version) {}
    @FunctionalInterface interface HostResolver {
        Map<String,EntityId> resolve(TenantId tenant, String source, Set<String> hostIds);
    }
    static void validatePage(TenantId tenant, String source, String dataMode, List<ExternalProblem> problems, Instant receivedAt) {
        if (problems.size() > 100 || !Set.of("labeled-fixture", "zabbix-jsonrpc").contains(dataMode)
            || problems.stream().anyMatch(p -> !p.tenantId().equals(tenant) || !p.sourceInstanceId().equals(source) || p.observedAt().isAfter(receivedAt))
            || problems.stream().map(ExternalProblem::problemEventId).distinct().count() != problems.size()) throw new IllegalArgumentException("Invalid incident import page");
    }
}
