package com.acme.opsweave.telemetry.infrastructure;

import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Labeled in-memory metric catalog. Not a production store and not a place for metric points. */
public final class InMemoryMetricDefinitionStore implements MetricDefinitionStore {
    private final ConcurrentHashMap<Key, MetricDefinition> definitions = new ConcurrentHashMap<>();

    @Override
    public void upsert(MetricDefinition definition) {
        Key key = new Key(definition.tenantId(), definition.id());
        definitions.compute(key, (ignored, existing) -> {
            long version = existing == null ? definition.version() : existing.version() + 1;
            return new MetricDefinition(
                definition.id(),
                definition.tenantId(),
                definition.name(),
                definition.displayName(),
                definition.entityType(),
                definition.unit(),
                definition.valueType(),
                definition.metricType(),
                definition.dimensions(),
                definition.origin(),
                definition.externalMapping(),
                definition.lifecycle(),
                version
            );
        });
    }

    @Override
    public int retireMissing(TenantId tenantId, String sourceInstanceId, Set<String> observedExternalIds) {
        Set<String> observed = Set.copyOf(observedExternalIds);
        int retired = 0;
        for (MetricDefinition definition : List.copyOf(definitions.values())) {
            if (!definition.tenantId().equals(tenantId)) {
                continue;
            }
            var mapping = definition.externalMapping();
            if (!mapping.sourceInstanceId().equals(sourceInstanceId) || observed.contains(mapping.externalId())) {
                continue;
            }
            if (definition.lifecycle() == MetricLifecycle.INACTIVE) {
                continue;
            }
            definitions.put(new Key(tenantId, definition.id()), new MetricDefinition(
                definition.id(),
                definition.tenantId(),
                definition.name(),
                definition.displayName(),
                definition.entityType(),
                definition.unit(),
                definition.valueType(),
                definition.metricType(),
                definition.dimensions(),
                definition.origin(),
                definition.externalMapping(),
                MetricLifecycle.INACTIVE,
                definition.version() + 1
            ));
            retired++;
        }
        return retired;
    }

    @Override
    public Optional<MetricDefinition> find(TenantId tenantId, String id) {
        return Optional.ofNullable(definitions.get(new Key(tenantId, id)));
    }

    @Override
    public List<MetricDefinition> list(TenantId tenantId) {
        List<MetricDefinition> result = new ArrayList<>();
        for (MetricDefinition definition : definitions.values()) {
            if (definition.tenantId().equals(tenantId)) {
                result.add(definition);
            }
        }
        return List.copyOf(result);
    }

    private record Key(TenantId tenantId, String id) {}
}
