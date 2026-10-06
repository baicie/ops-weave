package com.acme.opsweave.telemetry.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One source item bound to a catalog metric. Many bindings may share one {@link MetricDefinition}.
 */
public record MetricBinding(
    TenantId tenantId,
    String sourceType,
    String sourceInstanceId,
    String externalItemId,
    EntityId entityId,
    String hostExternalId,
    String metricKey,
    Map<String, String> fixedDimensions,
    String sourceUnit,
    String valueTransform,
    int mappingRevision,
    MetricLifecycle lifecycle,
    long version,
    MetricMappingPin mappingPin
) {
    /** Old persisted bindings remain explicitly unpinned until an authorized maintenance command. */
    public MetricBinding(TenantId tenantId,String sourceType,String sourceInstanceId,String externalItemId,EntityId entityId,
            String hostExternalId,String metricKey,Map<String,String> fixedDimensions,String sourceUnit,String valueTransform,
            int mappingRevision,MetricLifecycle lifecycle,long version) {
        this(tenantId,sourceType,sourceInstanceId,externalItemId,entityId,hostExternalId,metricKey,fixedDimensions,sourceUnit,valueTransform,mappingRevision,lifecycle,version,null);
    }
    public MetricBinding {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        Objects.requireNonNull(externalItemId, "externalItemId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(hostExternalId, "hostExternalId");
        Objects.requireNonNull(metricKey, "metricKey");
        Objects.requireNonNull(fixedDimensions, "fixedDimensions");
        Objects.requireNonNull(sourceUnit, "sourceUnit");
        Objects.requireNonNull(valueTransform, "valueTransform");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (sourceType.isBlank() || sourceInstanceId.isBlank() || externalItemId.isBlank()
            || hostExternalId.isBlank() || metricKey.isBlank() || valueTransform.isBlank()) {
            throw new IllegalArgumentException("Metric binding identity fields must be present");
        }
        if (mappingRevision < 1 || version < 1) {
            throw new IllegalArgumentException("Metric binding revision and version must start at 1");
        }
        fixedDimensions = Map.copyOf(new LinkedHashMap<>(fixedDimensions));
        if(mappingPin!=null&&mappingPin.revision()!=mappingRevision)throw new IllegalArgumentException("Metric mapping revision differs from pin");
    }

    public MetricBinding withPin(MetricMappingPin pin,long nextVersion) {
        return new MetricBinding(tenantId,sourceType,sourceInstanceId,externalItemId,entityId,hostExternalId,metricKey,
            fixedDimensions,sourceUnit,valueTransform,mappingRevision,lifecycle,nextVersion,pin);
    }

    /** Source refresh can observe metadata, but cannot silently replace a previously selected mapping. */
    public static void requireRefreshCompatible(MetricBinding existing,MetricBinding incoming) {
        if(existing!=null&&existing.mappingPin()!=null&&!existing.mappingPin().equals(incoming.mappingPin()))
            throw new IllegalStateException("Metric mapping changed; explicit maintenance required");
    }
}
