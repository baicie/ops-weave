package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.EntityId;
import java.util.*;

public final class BrowseEntitiesUseCase {
    private final AuthorizationService authorization;
    private final InventoryQuery inventory;
    public BrowseEntitiesUseCase(AuthorizationService authorization, InventoryQuery inventory) {
        this.authorization = authorization; this.inventory = inventory;
    }
    public Result execute(Principal principal, EntityPageQuery query) {
        if (!principal.has(Permission.ENTITY_READ)) return new Result(true, List.of(), null);
        boolean all = authorization.authorize(principal, ResourceRef.anyEntity(principal.tenantId()), Permission.ENTITY_READ).allowed();
        var ids = new HashSet<EntityId>();
        if (!all) for (var resource : principal.resourceScope().allowedResources()) {
            if (!resource.tenantId().equals(principal.tenantId()) || !resource.type().equals("entity") || resource.id().equals("*")) continue;
            if (authorization.authorize(principal, resource, Permission.ENTITY_READ).denied()) continue;
            try { ids.add(EntityId.parse(resource.id())); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid trusted entity scope"); }
        }
        var visibility = new EntityVisibility(all, ids);
        if (!all && ids.isEmpty()) return new Result(false, List.of(), null);
        var fetched = inventory.page(principal.tenantId(), visibility, query);
        if (fetched.size() > query.limit() + 1) throw new IllegalStateException("Inventory query exceeded limit");
        String previous = query.after() == null ? "" : query.after().value().toString();
        for (var entity : fetched) {
            if (!entity.tenantId().equals(principal.tenantId()) || !visibility.includes(entity.id())
                || authorization.authorize(principal, ResourceRef.entity(entity.tenantId(), entity.id()), Permission.ENTITY_READ).denied()
                || entity.id().value().toString().compareTo(previous) <= 0) throw new IllegalStateException("Inventory query violated scope or ordering");
            previous = entity.id().value().toString();
        }
        var items = fetched.stream().limit(query.limit()).toList();
        return new Result(false, items, fetched.size() > query.limit() ? items.getLast().id() : null);
    }
    public record Result(boolean forbidden, List<InventoryQuery.EntityView> items, EntityId nextCursor) {}
}
