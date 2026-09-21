package com.acme.opsweave.inventory.infrastructure;

import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Labeled in-memory inventory. Not a production store and not a silent fixture for live sources. */
public final class InMemoryInventoryStore implements InventoryQuery, InventoryWritePort {
    private final ConcurrentHashMap<StoreKey, Entity> entities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ExternalObjectKey, ExternalLink> links = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Observation> observations = new ConcurrentHashMap<>();

    @Override
    public Optional<EntityView> find(TenantId tenantId, EntityId entityId) {
        return Optional.ofNullable(entities.get(new StoreKey(tenantId, entityId))).map(this::view);
    }

    @Override
    public List<EntityView> list(TenantId tenantId) {
        List<EntityView> result = new ArrayList<>();
        for (Entity entity : entities.values()) {
            if (entity.tenantId().equals(tenantId)) {
                result.add(view(entity));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void upsert(Entity entity, Observation observation, ExternalLink link) {
        StoreKey key = new StoreKey(entity.tenantId(), entity.id());
        entities.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return entity;
            }
            return new Entity(
                existing.id(),
                existing.tenantId(),
                entity.entityType(),
                entity.name(),
                entity.lifecycle(),
                existing.version() + 1,
                entity.lastSeen(),
                entity.attributes()
            );
        });
        observations.put(observation.id(), observation);
        links.put(link.key(), link);
    }

    @Override
    public int retireMissing(TenantId tenantId, String sourceInstanceId, String externalType, Set<String> seenExternalIds) {
        Set<String> seen = Set.copyOf(seenExternalIds);
        Set<EntityId> retired = new HashSet<>();
        for (ExternalLink link : List.copyOf(links.values())) {
            ExternalObjectKey key = link.key();
            if (!key.tenantId().equals(tenantId) || !key.sourceInstanceId().equals(sourceInstanceId)) {
                continue;
            }
            if (!key.externalType().equals(externalType) || seen.contains(key.externalId())) {
                continue;
            }
            StoreKey storeKey = new StoreKey(tenantId, link.entityId());
            Entity entity = entities.get(storeKey);
            if (entity == null || entity.lifecycle() == Lifecycle.INACTIVE || !retired.add(entity.id())) {
                continue;
            }
            entities.put(storeKey, new Entity(
                entity.id(),
                entity.tenantId(),
                entity.entityType(),
                entity.name(),
                Lifecycle.INACTIVE,
                entity.version() + 1,
                entity.lastSeen(),
                entity.attributes()
            ));
        }
        return retired.size();
    }

    public Optional<ExternalLink> linkOf(ExternalObjectKey key) {
        return Optional.ofNullable(links.get(key));
    }

    public Optional<Observation> observation(String id) {
        return Optional.ofNullable(observations.get(id));
    }

    public Map<String, Observation> observations() {
        return Map.copyOf(observations);
    }

    private EntityView view(Entity entity) {
        return new EntityView(
            entity.id(),
            entity.tenantId(),
            entity.entityType(),
            entity.name(),
            entity.lifecycle().name(),
            entity.version(),
            entity.attributes()
        );
    }

    private record StoreKey(TenantId tenantId, EntityId entityId) {}
}
