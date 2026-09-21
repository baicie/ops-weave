package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class EntityIds {
    private EntityIds() {}

    public static EntityId fromExternal(ExternalObjectKey key) {
        String material = key.tenantId().value()
            + '\0' + key.sourceInstanceId()
            + '\0' + key.externalType()
            + '\0' + key.externalId()
            + '\0' + key.generation();
        return new EntityId(UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)));
    }
}
