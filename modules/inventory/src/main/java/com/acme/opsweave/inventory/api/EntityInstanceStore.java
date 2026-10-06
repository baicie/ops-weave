package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.Entity;
import com.acme.opsweave.inventory.domain.EntityModelPin;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.UUID;

/** Controlled model-backed entity instance writes, separate from source scan writes. */
public interface EntityInstanceStore {
    WriteResult write(TenantId tenant, UUID requestId, Entity entity, Long expectedVersion);
    default WriteResult write(TenantId tenant, UUID requestId, Entity entity, Long expectedVersion,
                              String modelId, int modelRevision, String modelDigest) {
        boolean absent = modelId == null && modelRevision == 0 && modelDigest == null
            || "".equals(modelId) && modelRevision == 0 && "".equals(modelDigest);
        EntityModelPin pin = absent ? null : new EntityModelPin(modelId, modelRevision, modelDigest);
        if (entity.model() != null && !entity.model().equals(pin)) throw new Conflict("Entity model pin mismatch");
        return write(tenant, requestId, entity.withModelPin(pin), expectedVersion);
    }
    /** Convenience overload for callers that already have a required update version. */
    default WriteResult write(TenantId tenant, UUID requestId, Entity entity, long expectedVersion) {
        return write(tenant, requestId, entity, Long.valueOf(expectedVersion));
    }
    record WriteResult(Entity entity, boolean replayed) {}
    final class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }
    final class Access extends RuntimeException { public Access(String message) { super(message); } }
}
