package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.inventory.api.EntityDeletionStore;
import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.domain.EntityDeletion;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The operational delete entry. One clearable object, one reasoned command, one immutable receipt.
 *
 * <p>The command is idempotent on its request id, requires tenant-level {@code entity.manage}, and
 * refuses a protected target instead of cascading. A soft delete keeps the entity row, so old Incident
 * and Evidence records that reference it keep resolving and are never rewritten or removed.
 */
public final class EntityDeletionService {
    public record Result(EntityDeletion.Receipt receipt, boolean replayed) {}

    private final AuthorizationService authorization;
    private final InventoryQuery entities;
    private final EntityDeletionStore store;
    private final Clock clock;

    public EntityDeletionService(AuthorizationService authorization, InventoryQuery entities,
                                 EntityDeletionStore store, Clock clock) {
        this.authorization = Objects.requireNonNull(authorization);
        this.entities = Objects.requireNonNull(entities);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result delete(Principal p, EntityDeletion.Command command) {
        Objects.requireNonNull(command, "command");
        authorize(p, command.entityId());
        var prior = store.command(p.tenantId(), command.requestId());
        if (prior.isPresent()) {
            prior.get().require(command);
            return new Result(prior.get(), true);
        }
        var entity = entities.find(p.tenantId(), command.entityId())
            .orElseThrow(() -> new EntityDeletionStore.NotFound("Entity missing"));
        if (store.protectedTarget(p.tenantId(), command.entityId())) {
            throw new EntityDeletion.Conflict("ENTITY_DELETION_PROTECTED");
        }
        // Already deleted: a new request is an explicit conflict, a replay above returns the original.
        if ("DELETED".equals(entity.lifecycle())) throw new EntityDeletion.Conflict("ENTITY_DELETION_CONFLICT");
        if (!store.softDelete(p.tenantId(), command.entityId(), entity.version())) {
            throw new EntityDeletion.Conflict("ENTITY_DELETION_CONFLICT");
        }
        var receipt = new EntityDeletion.Receipt(command.requestId(), command.commandDigest(), now(),
            command.entityId(), EntityDeletion.Outcome.SOFT_DELETED, 1, 0, 0, command.reason().trim());
        receipt.require(command);
        store.addCommand(p.tenantId(), receipt);
        return new Result(receipt, false);
    }

    public EntityDeletion.Receipt receipt(Principal p, UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (!p.has(Permission.ENTITY_READ)) throw new EntityDeletionStore.Access("Entity read denied");
        return store.command(p.tenantId(), requestId).orElseThrow(() -> new EntityDeletionStore.NotFound("Deletion receipt missing"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /** Tenant-level management authority: an object-scoped grant may read, never delete. */
    private void authorize(Principal p, EntityId id) {
        if (!p.has(Permission.ENTITY_MANAGE)
            || authorization.authorize(p, ResourceRef.entity(p.tenantId(), id), Permission.ENTITY_MANAGE).denied()) {
            throw new EntityDeletionStore.Access("Entity management denied");
        }
    }
}
