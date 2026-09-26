package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.SourceItemWritePort;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import java.util.Objects;
import java.util.Set;

/**
 * Development-only fenced item writes. The lease check is a real guard against a lost, superseded
 * or expired scan, but the catalog write and the check are not one transaction: a production store
 * must fence them together, which the PostgreSQL adapter does.
 */
public final class InMemorySourceItemWrites implements SourceItemWritePort {
    private final InventoryWritePort leases;
    private final MetricDefinitionStore definitions;

    public InMemorySourceItemWrites(InventoryWritePort leases, MetricDefinitionStore definitions) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
    }

    @Override
    public void upsert(SourceScan.Token scan, MetricDefinition definition, MetricBinding binding) {
        leases.renewScan(scan);
        definitions.upsert(definition);
        definitions.upsert(binding);
        leases.renewScan(scan);
    }

    @Override
    public int retireMissing(SourceScan.Token scan, Set<String> observedExternalIds) {
        leases.renewScan(scan);
        int retired = definitions.retireMissing(
            scan.scope().tenantId(), scan.scope().sourceInstanceId(), observedExternalIds
        );
        leases.renewScan(scan);
        return retired;
    }
}
