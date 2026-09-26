package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import java.util.Set;

/** Constructed by the authorization use case, never deserialized from an HTTP request. */
public record EntityVisibility(boolean all, Set<EntityId> ids) {
    public EntityVisibility {
        ids = Set.copyOf(ids);
        if (ids.size() > 1000 || all && !ids.isEmpty()) throw new IllegalArgumentException("Entity scope exceeds query budget");
    }
    public boolean includes(EntityId id) { return all || ids.contains(id); }
}
