package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.AuthorizationDecision;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.inventory.api.InventoryQuery;
import com.acme.opsweave.inventory.api.InventoryQuery.EntityView;
import com.acme.opsweave.sharedkernel.EntityId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class GetEntityUseCase {
    private final AuthorizationService authorization;
    private final InventoryQuery inventory;

    public GetEntityUseCase(AuthorizationService authorization, InventoryQuery inventory) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public EntityAccess get(Principal principal, EntityId entityId) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(entityId, "entityId");
        ResourceRef resource = ResourceRef.entity(principal.tenantId(), entityId);
        AuthorizationDecision decision = authorization.authorize(principal, resource, Permission.ENTITY_READ);
        if (decision.denied()) {
            return EntityAccess.forbidden();
        }
        return inventory.find(principal.tenantId(), entityId)
            .map(EntityAccess::found)
            .orElseGet(EntityAccess::missing);
    }

    public ListResult list(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        AuthorizationDecision decision = authorization.authorize(
            principal,
            ResourceRef.anyEntity(principal.tenantId()),
            Permission.ENTITY_READ
        );
        if (decision.denied()) {
            return ListResult.forbidden();
        }
        List<EntityView> visible = inventory.list(principal.tenantId()).stream()
            .filter(view -> principal.resourceScope().includes(ResourceRef.entity(principal.tenantId(), view.id())))
            .toList();
        return ListResult.visible(visible);
    }

    public record EntityAccess(EntityAccessKind kind, Optional<EntityView> entity) {
        public static EntityAccess found(EntityView view) {
            return new EntityAccess(EntityAccessKind.FOUND, Optional.of(view));
        }

        public static EntityAccess forbidden() {
            return new EntityAccess(EntityAccessKind.FORBIDDEN, Optional.empty());
        }

        public static EntityAccess missing() {
            return new EntityAccess(EntityAccessKind.MISSING, Optional.empty());
        }
    }

    public record ListResult(EntityAccessKind kind, List<EntityView> entities) {
        public static ListResult visible(List<EntityView> entities) {
            return new ListResult(EntityAccessKind.FOUND, List.copyOf(entities));
        }

        public static ListResult forbidden() {
            return new ListResult(EntityAccessKind.FORBIDDEN, List.of());
        }
    }
}
