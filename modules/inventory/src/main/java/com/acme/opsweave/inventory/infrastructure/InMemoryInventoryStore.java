package com.acme.opsweave.inventory.infrastructure;

import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.api.InventoryWritePort;
import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.ExternalLink;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.inventory.domain.Observation;
import com.acme.opsweave.inventory.domain.EntityPageQuery;
import com.acme.opsweave.inventory.domain.EntityVisibility;
import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Labeled in-memory inventory. Not a production store and not a silent fixture for live sources. */
public final class InMemoryInventoryStore implements InventoryQuery, InventoryWritePort, com.acme.opsweave.inventory.api.ObservationReader, com.acme.opsweave.inventory.api.SourceReviewStore, com.acme.opsweave.inventory.api.AssetIdentityStore, com.acme.opsweave.inventory.api.SourceReceiptCapacityReader {
    @Override public int snapshotReceipts(com.acme.opsweave.sharedkernel.TenantId tenantId, String sourceInstanceId) { return 0; }
    @Override public int correctionReceipts(com.acme.opsweave.sharedkernel.TenantId tenantId, String sourceInstanceId) { return 0; }
    private final ConcurrentHashMap<StoreKey, Entity> entities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ExternalObjectKey, ExternalLink> links = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Observation> observations = new ConcurrentHashMap<>();
    private final Map<StoreKey, Entity> primarySnapshots = new java.util.HashMap<>();
    private final Map<String, com.acme.opsweave.inventory.domain.SourceReview> reviews = new java.util.HashMap<>();
    private final Map<StoreKey, java.util.UUID> activeReviews = new java.util.HashMap<>();
    private final Map<ExternalObjectKey, java.util.UUID> supplementalBindings = new java.util.HashMap<>();
    private final Map<String, com.acme.opsweave.inventory.api.SourceReviewStore.Receipt> receipts = new java.util.HashMap<>();
    private final Map<String, com.acme.opsweave.inventory.domain.AssetIdentity> identityRecords = new java.util.HashMap<>();
    private final Map<String, java.util.UUID> identityBindings = new java.util.HashMap<>();
    private final Map<String, com.acme.opsweave.inventory.api.AssetIdentityStore.Receipt> identityReceipts = new java.util.HashMap<>();
    private final Map<SourceScan.Scope, SourceScan.Lease> scans = new java.util.HashMap<>();
    private final java.time.Clock scanClock;
    public InMemoryInventoryStore() { this(java.time.Clock.systemUTC()); }
    public InMemoryInventoryStore(java.time.Clock clock) { scanClock = Objects.requireNonNull(clock); }
    @Override public synchronized SourceScan.Token beginScan(SourceScan.Scope scope, java.util.UUID runId) {
        var value = SourceScan.Lease.acquire(scope, runId, scans.get(scope), scanClock.instant());
        scans.put(scope, value);
        return value.token();
    }
    private SourceScan.Lease requireScan(SourceScan.Token scan) {
        var lease = scans.get(scan.scope());
        if (lease == null) throw new SourceScan.Failure(SourceScan.Code.LOST);
        lease.require(scan, scanClock.instant());
        return lease;
    }
    @Override public synchronized void renewScan(SourceScan.Token scan) {
        var lease = requireScan(scan);
        scans.put(scan.scope(), lease.renew(scan, scanClock.instant()));
    }
    @Override public synchronized void releaseScan(SourceScan.Token scan) {
        var lease = scans.get(scan.scope());
        if (lease != null && lease.token().equals(scan)) scans.put(scan.scope(), lease.release());
    }
    private void unmanaged(SourceScan.Scope scope) {
        var lease = scans.get(scope);
        if (lease != null && !lease.released()) throw new SourceScan.Failure(SourceScan.Code.BUSY);
    }
    @Override public synchronized void upsert(SourceScan.Token scan, Entity entity, Observation observation, ExternalLink link) {
        var renewed = requireScan(scan).renew(scan, scanClock.instant());
        scan.scope().require(link.key());
        upsertInside(entity, observation, link);
        scans.put(scan.scope(), renewed);
    }
    @Override public synchronized int finishScan(SourceScan.Token scan, Set<String> ids) {
        requireScan(scan);
        int result = retireInside(scan.scope().tenantId(), scan.scope().sourceInstanceId(), scan.scope().externalType(), ids);
        scans.put(scan.scope(), scans.get(scan.scope()).release());
        return result;
    }

    @Override
    public synchronized Optional<EntityView> find(TenantId tenantId, EntityId entityId) {
        return Optional.ofNullable(entities.get(new StoreKey(tenantId, entityId))).map(this::view);
    }

    @Override
    public synchronized List<EntityView> list(TenantId tenantId) {
        List<EntityView> result = new ArrayList<>();
        for (Entity entity : entities.values()) {
            if (entity.tenantId().equals(tenantId)) {
                result.add(view(entity));
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized void upsert(Entity entity, Observation observation, ExternalLink link) {
        unmanaged(new SourceScan.Scope(link.key().tenantId(),link.key().sourceInstanceId(),link.key().externalType()));
        upsertInside(entity,observation,link);
    }
    private void upsertInside(Entity entity, Observation observation, ExternalLink link) {
        Observation.checkWrite(entity, observation, link);
        String observationKey = entity.tenantId().value() + '\0' + observation.id();
        var oldObservation = observations.get(observationKey);
        if (oldObservation != null) {
            if (!oldObservation.equals(observation)) throw new IllegalStateException("Immutable observation conflict");
            return;
        }
        var existingLink = links.get(link.key());
        if (existingLink != null && !existingLink.entityId().equals(entity.id())) throw new IllegalStateException("External link reassignment requires resolution");
        if (links.values().stream().anyMatch(old -> old.key().tenantId().equals(entity.tenantId()) && old.entityId().equals(entity.id())
            && !old.key().equals(link.key()))) throw new IllegalStateException("Multiple sources require explicit field authority");
        StoreKey key = new StoreKey(entity.tenantId(), entity.id());
        entities.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return entity;
            }
            if (entity.lastSeen().isBefore(existing.lastSeen())) return existing;
            var projected = com.acme.opsweave.inventory.domain.FieldAuthority.project(new Entity(
                existing.id(),
                existing.tenantId(),
                entity.entityType(),
                entity.name(),
                entity.lifecycle(),
                existing.version() + 1,
                entity.lastSeen(),
                entity.attributes()
            ), active(key), existing.version() + 1);
            if (primarySnapshots.containsKey(key)) primarySnapshots.put(key, entity);
            return projected;
        });
        observations.put(observationKey, observation);
        links.put(link.key(), link);
    }

    @Override
    public synchronized List<EntityView> page(TenantId tenant, EntityVisibility visibility, EntityPageQuery query) {
        String search = query.search().toLowerCase(java.util.Locale.ROOT);
        return entities.values().stream().filter(e -> e.tenantId().equals(tenant) && visibility.includes(e.id()))
            .filter(e -> query.after() == null || e.id().value().toString().compareTo(query.after().value().toString()) > 0)
            .filter(e -> query.lifecycle() == null || e.lifecycle() == query.lifecycle())
            .filter(e -> query.entityType().isEmpty() || e.entityType().equals(query.entityType()))
            .filter(e -> search.isEmpty() || e.name().toLowerCase(java.util.Locale.ROOT).contains(search)
                || Objects.toString(e.attributes().get("ip"), "").toLowerCase(java.util.Locale.ROOT).contains(search))
            .sorted(java.util.Comparator.comparing(e -> e.id().value().toString())).limit(query.limit() + 1L).map(this::view).toList();
    }

    @Override
    public synchronized int retireMissing(TenantId tenantId, String sourceInstanceId, String externalType, Set<String> observedExternalIds) {
        unmanaged(new SourceScan.Scope(tenantId,sourceInstanceId,externalType));return retireInside(tenantId,sourceInstanceId,externalType,observedExternalIds);
    }
    private int retireInside(TenantId tenantId,String sourceInstanceId,String externalType,Set<String> observedExternalIds){
        Set<String> observed = Set.copyOf(observedExternalIds);
        Set<EntityId> retired = new HashSet<>();
        for (ExternalLink link : List.copyOf(links.values())) {
            ExternalObjectKey key = link.key();
            if (!key.tenantId().equals(tenantId) || !key.sourceInstanceId().equals(sourceInstanceId)) {
                continue;
            }
            if (!key.externalType().equals(externalType) || observed.contains(key.externalId())) {
                continue;
            }
            StoreKey storeKey = new StoreKey(tenantId, link.entityId());
            Entity entity = entities.get(storeKey);
            if (entity == null || entity.lifecycle() == Lifecycle.INACTIVE || !retired.add(entity.id())) {
                continue;
            }
            primarySnapshots.computeIfPresent(storeKey, (ignored, primary) -> com.acme.opsweave.inventory.domain.FieldAuthority.inactive(primary));
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

    public Optional<Observation> observation(TenantId tenant, String id) {
        return Optional.ofNullable(observations.get(tenant.value() + '\0' + id));
    }

    @Override public synchronized List<com.acme.opsweave.inventory.api.ObservationReader.Entry> observations(TenantId tenant, EntityId entity,
            com.acme.opsweave.inventory.domain.ObservationQuery query) {
        return observations.values().stream().filter(o -> o.key().tenantId().equals(tenant) && o.entityId().equals(entity) && query.includes(o))
            .sorted(java.util.Comparator.comparing(Observation::id)).limit(query.limit() + 1L)
            .map(o -> new com.acme.opsweave.inventory.api.ObservationReader.Entry(o, true)).toList();
    }

    public Map<String, Observation> observations() {
        return Map.copyOf(observations);
    }

    private com.acme.opsweave.inventory.domain.SourceReview active(StoreKey key) {
        var id = activeReviews.get(key); return id == null ? null : reviews.get(key.tenantId().value() + '\0' + id);
    }
    @Override public synchronized com.acme.opsweave.inventory.domain.SourceReview stage(TenantId tenant, EntityId entity, Import input, java.time.Instant now) {
        if (!input.source().tenantId().equals(tenant)) throw new IllegalArgumentException("Import tenant mismatch");
        var key = new StoreKey(tenant, entity); var current = entities.get(key);
        if (current == null) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Entity missing");
        String reviewKey = tenant.value() + '\0' + input.id(); var old = reviews.get(reviewKey);
        if (old != null) {
            if (!old.entityId().equals(entity) || !input.matches(old)) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Import id reused");
            return old;
        }
        validateIdentity(tenant,entity,input.identity());
        var primary = primarySnapshots.getOrDefault(key, current);
        var review = input.stage(primary, current.version(), now);
        primarySnapshots.putIfAbsent(key, primary); reviews.put(reviewKey, review); return review;
    }
    @Override public synchronized com.acme.opsweave.inventory.domain.SourceReview decide(TenantId tenant, EntityId entity, String sourceId, java.util.UUID reviewId,
            com.acme.opsweave.inventory.domain.SourceReview.Command command, java.time.Instant now) {
        var key = new StoreKey(tenant, entity); String receiptKey = tenant.value() + '\0' + entity.value() + '\0' + command.requestId();
        var receipt = receipts.get(receiptKey);
        if (receipt != null) {
            if (!receipt.reviewId().equals(reviewId) || !receipt.command().equals(command) || !receipt.result().source().sourceInstanceId().equals(sourceId)) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Decision id reused");
            return receipt.result();
        }
        var review = reviews.get(tenant.value() + '\0' + reviewId); var current = entities.get(key);
        if (review == null || current == null || !review.entityId().equals(entity) || !review.source().sourceInstanceId().equals(sourceId)) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Review missing");
        var changed = review.decide(command, current.version(), now);
        Entity projection = current;
        if (command.action() == com.acme.opsweave.inventory.domain.SourceReview.Action.ACCEPT) {
            validateIdentity(tenant,entity,review.identity());
            if (activeReviews.containsKey(key) || supplementalBindings.containsKey(review.source())) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Revoke the existing binding before accepting another");
            if (observations.containsKey(tenant.value() + '\0' + "source-review:" + reviewId)) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Observation id is already retained");
            projection = com.acme.opsweave.inventory.domain.FieldAuthority.project(primarySnapshots.get(key), changed, current.version() + 1);
        } else if (command.action() == com.acme.opsweave.inventory.domain.SourceReview.Action.REVOKE) {
            if (!reviewId.equals(activeReviews.get(key))) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Review is no longer active");
            projection = com.acme.opsweave.inventory.domain.FieldAuthority.project(primarySnapshots.get(key), null, current.version() + 1);
        }
        if (command.action() == com.acme.opsweave.inventory.domain.SourceReview.Action.ACCEPT) {
            activeReviews.put(key, reviewId); supplementalBindings.put(review.source(), reviewId);
            var observation = com.acme.opsweave.inventory.domain.FieldAuthority.observation(changed);
            observations.put(tenant.value() + '\0' + observation.id(), observation);
        } else if (command.action() == com.acme.opsweave.inventory.domain.SourceReview.Action.REVOKE) {
            activeReviews.remove(key); supplementalBindings.remove(review.source());
        }
        entities.put(key, projection); reviews.put(tenant.value() + '\0' + reviewId, changed);
        receipts.put(receiptKey, new com.acme.opsweave.inventory.api.SourceReviewStore.Receipt(reviewId, command, changed)); return changed;
    }
    @Override public synchronized Page reviews(TenantId tenant, EntityId entity, String sourceId, java.util.UUID after, int limit) {
        if (limit < 1 || limit > 25) throw new IllegalArgumentException("Invalid review limit");
        var items = reviews.values().stream().filter(r -> r.tenantId().equals(tenant) && r.entityId().equals(entity) && r.source().sourceInstanceId().equals(sourceId)
            && (after == null || r.id().toString().compareTo(after.toString()) > 0)).sorted(java.util.Comparator.comparing(r -> r.id().toString())).limit(limit + 1L).toList();
        var active = active(new StoreKey(tenant, entity));
        return new Page(items, active != null && active.source().sourceInstanceId().equals(sourceId) ? active : null);
    }

    private void validateIdentity(TenantId tenant, EntityId entity, com.acme.opsweave.inventory.domain.AssetIdentity.Pin pin) {
        if (pin == null) return;
        var value = identityRecords.get(tenant.value() + '\0' + pin.id());
        if (value == null) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Identity missing");
        value.requirePin(tenant,entity,pin);
    }
    @Override public synchronized com.acme.opsweave.inventory.api.AssetIdentityStore.Receipt change(TenantId tenant, EntityId entity, String namespace,
            com.acme.opsweave.inventory.domain.AssetIdentity.Command command, java.time.Instant now) {
        com.acme.opsweave.inventory.domain.AssetIdentity.namespace(namespace);
        var key = new StoreKey(tenant,entity); String receiptKey = tenant.value() + '\0' + command.requestId();
        var prior = identityReceipts.get(receiptKey);
        if (prior != null) {
            if (!prior.command().equals(command) || !prior.identity().entityId().equals(entity) || !prior.identity().namespace().equals(namespace)) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Identity request reused");
            return prior;
        }
        var current = entities.get(key);
        if (current == null || current.version() != command.expectedEntityVersion()) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Entity changed");
        if (!current.entityType().equalsIgnoreCase("host")) throw new IllegalArgumentException("Asset UUID resolution requires a Host");
        String recordKey = tenant.value() + '\0' + command.identityId(); com.acme.opsweave.inventory.domain.AssetIdentity changed;
        if (command.action() == com.acme.opsweave.inventory.domain.AssetIdentity.Action.ASSERT) {
            String binding = tenant.value() + '\0' + namespace + '\0' + command.value();
            var owned = identityRecords.values().stream().filter(r -> r.tenantId().equals(tenant) && r.entityId().equals(entity)).toList();
            if (identityRecords.containsKey(recordKey) || identityBindings.containsKey(binding)) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Identity is already claimed");
            if (owned.size() >= com.acme.opsweave.inventory.domain.AssetIdentity.MAX_RECORDS || owned.stream().filter(com.acme.opsweave.inventory.domain.AssetIdentity::active).count() >= com.acme.opsweave.inventory.domain.AssetIdentity.MAX_ACTIVE) throw new IllegalStateException("Identity retention budget exhausted");
            changed = new com.acme.opsweave.inventory.domain.AssetIdentity(command.identityId(),tenant,entity,namespace,command.value(),command.actor(),command.reason(),now,null);
            identityBindings.put(binding,changed.id());
        } else {
            var previous = identityRecords.get(recordKey);
            if (previous == null || !previous.entityId().equals(entity) || !previous.namespace().equals(namespace)) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Identity missing");
            var active = active(key);
            if (active != null && active.identity() != null && active.identity().id().equals(previous.id())) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Revoke dependent source fields first");
            changed = previous.revoke(command,now); identityBindings.remove(tenant.value() + '\0' + namespace + '\0' + previous.value());
        }
        var receipt = new com.acme.opsweave.inventory.api.AssetIdentityStore.Receipt(command,changed,current.version()+1);
        entities.put(key,new Entity(entity,tenant,current.entityType(),current.name(),current.lifecycle(),current.version()+1,current.lastSeen(),current.attributes()));
        identityRecords.put(recordKey,changed); identityReceipts.put(receiptKey,receipt); return receipt;
    }
    @Override public synchronized List<com.acme.opsweave.inventory.domain.AssetIdentity> identities(TenantId tenant, EntityId entity, String namespace, java.util.UUID after, int limit) {
        com.acme.opsweave.inventory.domain.AssetIdentity.namespace(namespace); if(limit < 1 || limit > 25) throw new IllegalArgumentException("Invalid identity limit");
        return identityRecords.values().stream().filter(r -> r.tenantId().equals(tenant) && r.entityId().equals(entity) && r.namespace().equals(namespace)
            && (after == null || r.id().toString().compareTo(after.toString()) > 0)).sorted(java.util.Comparator.comparing(r -> r.id().toString())).limit(limit+1L).toList();
    }
    @Override public synchronized Optional<com.acme.opsweave.inventory.api.AssetIdentityStore.Match> resolve(TenantId tenant, String namespace, String value) {
        com.acme.opsweave.inventory.domain.AssetIdentity.namespace(namespace); com.acme.opsweave.inventory.domain.AssetIdentity.value(value);
        var id = identityBindings.get(tenant.value() + '\0' + namespace + '\0' + value);
        if (id == null) return Optional.empty(); var identity = identityRecords.get(tenant.value() + '\0' + id); var entity = entities.get(new StoreKey(tenant,identity.entityId()));
        if (entity == null) throw new IllegalStateException("Identity target missing");
        return Optional.of(new com.acme.opsweave.inventory.api.AssetIdentityStore.Match(identity,entity.version()));
    }

    private EntityView view(Entity entity) {
        com.acme.opsweave.inventory.domain.EntityReadLimits.check(entity.attributes());
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
