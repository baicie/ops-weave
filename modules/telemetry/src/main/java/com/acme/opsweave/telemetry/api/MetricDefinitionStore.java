package com.acme.opsweave.telemetry.api;

import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MetricDefinitionStore {
    void upsert(MetricDefinition definition);

    /**
     * Inactivates definitions whose source item was absent from a finished offset scan.
     * The set is source presence, including items whose mapping was rejected.
     */
    int retireMissing(TenantId tenantId, String sourceInstanceId, Set<String> observedExternalIds);

    Optional<MetricDefinition> find(TenantId tenantId, String id);

    List<MetricDefinition> list(TenantId tenantId);
}
