package com.acme.opsweave.telemetry.api;

import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MetricDefinitionStore {
    void upsert(MetricDefinition definition);

    void upsert(MetricBinding binding);

    /**
     * Inactivates bindings whose source item was absent from a finished offset scan.
     * The set is source presence, including items whose mapping was rejected.
     * Catalog definitions stay in place.
     */
    int retireMissing(TenantId tenantId, String sourceInstanceId, Set<String> observedExternalIds);

    Optional<MetricDefinition> find(TenantId tenantId, String metricKey);

    Optional<MetricBinding> findBinding(TenantId tenantId, String sourceInstanceId, String externalItemId);

    List<MetricDefinition> list(TenantId tenantId);

    List<MetricBinding> listBindings(TenantId tenantId);
}
