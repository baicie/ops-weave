package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.inventory.api.InventoryQuery.EntityView;
import com.acme.opsweave.inventory.application.EntityAccessKind;
import com.acme.opsweave.inventory.application.GetEntityUseCase;
import com.acme.opsweave.sharedkernel.EntityId;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/entities")
public class EntityController {
    private final PrincipalContext principalContext;
    private final GetEntityUseCase getEntity;

    public EntityController(PrincipalContext principalContext, GetEntityUseCase getEntity) {
        this.principalContext = principalContext;
        this.getEntity = getEntity;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        var result = getEntity.list(principalContext.requirePrincipal());
        if (result.kind() == EntityAccessKind.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(Map.of("items", result.entities().stream().map(EntityController::body).toList()));
    }

    @GetMapping("/{entityId}")
    public ResponseEntity<?> get(@PathVariable String entityId) {
        EntityId id;
        try {
            id = EntityId.parse(entityId);
        } catch (RuntimeException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_entity_id"));
        }
        var access = getEntity.get(principalContext.requirePrincipal(), id);
        return switch (access.kind()) {
            case FOUND -> ResponseEntity.ok(body(access.entity().orElseThrow()));
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
            case MISSING -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        };
    }

    private static Map<String, Object> body(EntityView view) {
        return Map.of(
            "schemaVersion", "1.0",
            "id", view.id().value().toString(),
            "tenantId", view.tenantId().value(),
            "entityType", view.type(),
            "name", view.name(),
            "lifecycle", view.lifecycle(),
            "version", view.version(),
            "attributes", view.attributes()
        );
    }
}
