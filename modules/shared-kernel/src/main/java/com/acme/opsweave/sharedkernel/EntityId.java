package com.acme.opsweave.sharedkernel;

import java.util.Objects;
import java.util.UUID;

public record EntityId(UUID value) {
    public EntityId { Objects.requireNonNull(value, "entityId"); }
    public static EntityId parse(String text) { return new EntityId(UUID.fromString(text)); }
}
