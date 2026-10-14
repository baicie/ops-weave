package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.EntityDeletion;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the operational delete entry. The store resolves protection itself, so a stale request
 * can never delete a record that has become protected since the operator looked at it.
 */
public interface EntityDeletionStore {
    /** An entity that is still an endpoint of a current (not ended) relation is protected. */
    boolean protectedTarget(TenantId tenant, EntityId id);

    /** Soft delete with a version CAS: the row and every reference stay, only the lifecycle changes. */
    boolean softDelete(TenantId tenant, EntityId id, long expectedVersion);

    Optional<EntityDeletion.Receipt> command(TenantId tenant, UUID requestId);

    void addCommand(TenantId tenant, EntityDeletion.Receipt receipt);

    final class Access extends RuntimeException { public Access(String message) { super(message); } }
    final class NotFound extends RuntimeException { public NotFound(String message) { super(message); } }
}
