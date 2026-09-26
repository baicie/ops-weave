package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.EntityId;

/** Literal name/IP search; no user-defined sorting, SQL or expressions. */
public record EntityPageQuery(String search, Lifecycle lifecycle, String entityType, EntityId after, int limit) {
    public EntityPageQuery {
        search = search == null ? "" : search.strip();
        entityType = entityType == null ? "" : entityType.strip();
        if (search.length() > 100 || search.codePoints().anyMatch(Character::isISOControl)
            || (!entityType.isEmpty() && !entityType.matches("[a-z][a-z0-9-]{0,63}")) || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid entity page query");
    }
}
