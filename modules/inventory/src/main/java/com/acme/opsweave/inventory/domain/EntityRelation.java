package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One tenant-scoped relation instance. The relation model revision is immutable. */
public record EntityRelation(UUID id, TenantId tenantId, EntityId fromEntityId, String relationType,
        int relationRevision, EntityId toEntityId, Instant validFrom, Instant validTo,
        String sourceRef, String dataMode, long version) {
    public EntityRelation {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(fromEntityId);
        Objects.requireNonNull(toEntityId); Objects.requireNonNull(validFrom); Objects.requireNonNull(sourceRef);
        Objects.requireNonNull(dataMode);
        if (fromEntityId.equals(toEntityId)) throw new IllegalArgumentException("Relation endpoints must differ");
        if (relationType == null || !relationType.matches("(builtin|custom)\\.[a-z][a-z0-9_]{0,47}")) throw new IllegalArgumentException("Invalid relation type");
        if (relationRevision < 1 || relationRevision > 10000) throw new IllegalArgumentException("Invalid relation revision");
        if (validTo != null && !validTo.isAfter(validFrom)) throw new IllegalArgumentException("Invalid relation interval");
        if (sourceRef.isBlank() || sourceRef.length() > 128 || sourceRef.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid relation source");
        if (!java.util.Set.of("fixture", "zabbix-jsonrpc", "import", "unknown").contains(dataMode)) throw new IllegalArgumentException("Invalid relation data mode");
        if (version < 1) throw new IllegalArgumentException("Invalid relation version");
    }
}
