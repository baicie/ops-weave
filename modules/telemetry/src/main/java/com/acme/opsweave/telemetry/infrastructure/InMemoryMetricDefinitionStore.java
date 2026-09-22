package com.acme.opsweave.telemetry.infrastructure;

import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.api.MetricDefinitionStore;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricLifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Labeled in-memory metric catalog. Not a production store and not a place for metric points. */
public final class InMemoryMetricDefinitionStore implements MetricDefinitionStore {
    private final ConcurrentHashMap<DefinitionKey, MetricDefinition> definitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BindingKey, MetricBinding> bindings = new ConcurrentHashMap<>();

    @Override
    public void upsert(MetricDefinition definition) {
        DefinitionKey key = new DefinitionKey(definition.tenantId(), definition.metricKey());
        definitions.compute(key, (ignored, existing) -> new MetricDefinition(
            definition.tenantId(),
            definition.metricKey(),
            definition.displayName(),
            definition.unit(),
            definition.valueType(),
            definition.metricType(),
            definition.dimensionSchema(),
            existing == null ? definition.version() : existing.version() + 1
        ));
    }

    @Override
    public void upsert(MetricBinding binding) {
        BindingKey key = new BindingKey(binding.tenantId(), binding.sourceInstanceId(), binding.externalItemId());
        bindings.compute(key, (ignored, existing) -> new MetricBinding(
            binding.tenantId(),
            binding.sourceType(),
            binding.sourceInstanceId(),
            binding.externalItemId(),
            binding.entityId(),
            binding.hostExternalId(),
            binding.metricKey(),
            binding.fixedDimensions(),
            binding.sourceUnit(),
            binding.valueTransform(),
            binding.mappingRevision(),
            binding.lifecycle(),
            existing == null ? binding.version() : existing.version() + 1
        ));
    }

    @Override
    public int retireMissing(TenantId tenantId, String sourceInstanceId, Set<String> observedExternalIds) {
        Set<String> observed = Set.copyOf(observedExternalIds);
        int retired = 0;
        for (MetricBinding binding : List.copyOf(bindings.values())) {
            if (!binding.tenantId().equals(tenantId) || !binding.sourceInstanceId().equals(sourceInstanceId)) {
                continue;
            }
            if (observed.contains(binding.externalItemId()) || binding.lifecycle() == MetricLifecycle.INACTIVE) {
                continue;
            }
            bindings.put(new BindingKey(tenantId, sourceInstanceId, binding.externalItemId()), new MetricBinding(
                binding.tenantId(),
                binding.sourceType(),
                binding.sourceInstanceId(),
                binding.externalItemId(),
                binding.entityId(),
                binding.hostExternalId(),
                binding.metricKey(),
                binding.fixedDimensions(),
                binding.sourceUnit(),
                binding.valueTransform(),
                binding.mappingRevision(),
                MetricLifecycle.INACTIVE,
                binding.version() + 1
            ));
            retired++;
        }
        return retired;
    }

    @Override
    public Optional<MetricDefinition> find(TenantId tenantId, String metricKey) {
        return Optional.ofNullable(definitions.get(new DefinitionKey(tenantId, metricKey)));
    }

    @Override
    public Optional<MetricBinding> findBinding(TenantId tenantId, String sourceInstanceId, String externalItemId) {
        return Optional.ofNullable(bindings.get(new BindingKey(tenantId, sourceInstanceId, externalItemId)));
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

    @Override
    public List<MetricBinding> listBindings(TenantId tenantId) {
        List<MetricBinding> result = new ArrayList<>();
        for (MetricBinding binding : bindings.values()) {
            if (binding.tenantId().equals(tenantId)) {
                result.add(binding);
            }
        }
        return List.copyOf(result);
    }

    private record DefinitionKey(TenantId tenantId, String metricKey) {}

    private record BindingKey(TenantId tenantId, String sourceInstanceId, String externalItemId) {}
}
