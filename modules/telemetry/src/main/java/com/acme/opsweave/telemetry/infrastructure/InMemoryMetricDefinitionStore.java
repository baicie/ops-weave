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
public final class InMemoryMetricDefinitionStore implements MetricDefinitionStore, com.acme.opsweave.telemetry.api.MetricMappingStore {
    private final ConcurrentHashMap<DefinitionKey, MetricDefinition> definitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BindingKey, MetricBinding> bindings = new ConcurrentHashMap<>();

    @Override
    public synchronized void upsert(MetricDefinition definition) {
        var current=find(definition.tenantId(),definition.metricKey()).orElse(null);
        if(current!=null&&(!current.unit().equals(definition.unit())||current.metricType()!=definition.metricType()||current.valueType()!=definition.valueType()
            ||!current.dimensionSchema().equals(definition.dimensionSchema()))
            &&bindings.values().stream().anyMatch(b->b.tenantId().equals(definition.tenantId())&&b.metricKey().equals(definition.metricKey())&&b.mappingPin()!=null))
            throw new IllegalStateException("Pinned metric definition semantics changed");
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
    public synchronized void upsert(MetricBinding binding) {
        BindingKey key = new BindingKey(binding.tenantId(), binding.sourceInstanceId(), binding.externalItemId());
        bindings.compute(key, (ignored, existing) -> {
            MetricBinding.requireRefreshCompatible(existing,binding);
            var next=new MetricBinding(
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
            existing == null ? binding.version() : existing.version(),
            existing == null ? binding.mappingPin() : existing.mappingPin()
        );
            if(existing!=null && next.equals(existing))return existing;
            return next.mappingPin()==null ? new MetricBinding(next.tenantId(),next.sourceType(),next.sourceInstanceId(),next.externalItemId(),next.entityId(),
                next.hostExternalId(),next.metricKey(),next.fixedDimensions(),next.sourceUnit(),next.valueTransform(),next.mappingRevision(),next.lifecycle(),
                existing==null?next.version():next.version()+1,null) : next.withPin(next.mappingPin(),existing==null?next.version():next.version()+1);
        });
    }

    @Override
    public synchronized int retireMissing(TenantId tenantId, String sourceInstanceId, Set<String> observedExternalIds) {
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
                binding.version() + 1, binding.mappingPin()
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

    private final java.util.Map<String,com.acme.opsweave.telemetry.domain.MetricMappingReceipt> receipts=new java.util.HashMap<>();
    @Override public synchronized <T> T mappingTransaction(TenantId tenant,java.util.function.Function<Session,T> work) {
        var oldBindings=new java.util.HashMap<>(bindings);var oldReceipts=new java.util.HashMap<>(receipts);
        try { return work.apply(new Session() {
            private String key(String owner,java.util.UUID id){return tenant.value()+"\0"+owner+"\0"+id;}
            public Optional<MetricBinding> binding(String source,String item){return findBinding(tenant,source,item);}
            public Optional<MetricDefinition> definition(String name){return find(tenant,name);}
            public List<MetricBinding> bindings(com.acme.opsweave.identity.domain.ResourceScope scope){return listBindings(tenant).stream()
                .filter(b->scope.includes(com.acme.opsweave.identity.domain.ResourceRef.source(tenant,b.sourceInstanceId()))
                    &&scope.includes(com.acme.opsweave.identity.domain.ResourceRef.entity(tenant,b.entityId()))
                    &&scope.includes(com.acme.opsweave.identity.domain.ResourceRef.metric(tenant,b.metricKey()))).sorted(java.util.Comparator.comparing(MetricBinding::sourceInstanceId).thenComparing(MetricBinding::externalItemId)).limit(201).toList();}
            public Optional<com.acme.opsweave.telemetry.domain.MetricMappingReceipt> receipt(String owner,java.util.UUID id){return Optional.ofNullable(receipts.get(key(owner,id)));}
            public int receiptCount(String owner){return (int)receipts.keySet().stream().filter(k->k.startsWith(tenant.value()+"\0"+owner+"\0")).count();}
            public void replace(MetricBinding next,long expected){
                if(!next.tenantId().equals(tenant))throw new IllegalArgumentException();
                var identity=new BindingKey(tenant,next.sourceInstanceId(),next.externalItemId());
                var current=bindings.get(identity);if(current==null||current.version()!=expected)throw new com.acme.opsweave.telemetry.domain.MetricMappingFailure(com.acme.opsweave.telemetry.domain.MetricMappingFailure.Code.CONFLICT);
                bindings.put(identity,next);
            }
            public void addReceipt(String owner,com.acme.opsweave.telemetry.domain.MetricMappingReceipt receipt){
                if(!receipt.binding().tenantId().equals(tenant)||receipts.putIfAbsent(key(owner,receipt.requestId()),receipt)!=null)throw new IllegalStateException("Duplicate mapping receipt");
            }
        }); } catch(RuntimeException e){ bindings.clear();bindings.putAll(oldBindings);receipts.clear();receipts.putAll(oldReceipts);throw e;}
    }
    private record DefinitionKey(TenantId tenantId, String metricKey) {}

    private record BindingKey(TenantId tenantId, String sourceInstanceId, String externalItemId) {}
}
