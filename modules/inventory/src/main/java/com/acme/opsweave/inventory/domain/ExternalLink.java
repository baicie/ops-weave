package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import java.util.Objects;

public record ExternalLink(EntityId entityId, ExternalObjectKey key) {
    public ExternalLink {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(key, "key");
    }
}
