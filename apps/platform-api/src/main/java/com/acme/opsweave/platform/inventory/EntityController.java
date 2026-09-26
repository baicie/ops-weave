package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.inventory.api.InventoryQuery.EntityView;
import com.acme.opsweave.inventory.application.GetEntityUseCase;
import com.acme.opsweave.inventory.application.BrowseEntitiesUseCase;
import com.acme.opsweave.inventory.domain.EntityPageQuery;
import com.acme.opsweave.inventory.domain.Lifecycle;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import com.acme.opsweave.sharedkernel.EntityId;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/entities")
public class EntityController {
    private final PrincipalContext principalContext;
    private final GetEntityUseCase getEntity;
    private final BrowseEntitiesUseCase browse;
    private final String storage;

    public EntityController(PrincipalContext principalContext, GetEntityUseCase getEntity, BrowseEntitiesUseCase browse, InventoryWiring wiring) {
        this.principalContext = principalContext;
        this.getEntity = getEntity;
        this.browse = browse; storage = wiring.label();
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException("Use the paged entity endpoint for filters");
        var result = browse.execute(principalContext.requirePrincipal(), new EntityPageQuery("", null, "", null, 100));
        if (result.forbidden()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        // Never truncate a formerly complete list and present it as complete.
        if (result.nextCursor() != null) return ResponseEntity.unprocessableContent().body(Map.of("error", "PAGED_READ_REQUIRED"));
        return ResponseEntity.ok(Map.of("items", result.items().stream().map(EntityController::body).toList()));
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

    @GetMapping("/page")
    public ResponseEntity<?> page(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String lifecycle, @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "25") int limit, HttpServletRequest request) {
        if (!Set.of("q", "type", "lifecycle", "after", "limit").containsAll(request.getParameterMap().keySet())
            || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) throw new IllegalArgumentException("Invalid entity page parameters");
        if (after != null && !after.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid cursor");
        var query = new EntityPageQuery(q, lifecycle.isEmpty() ? null : Lifecycle.valueOf(lifecycle), type, after == null ? null : EntityId.parse(after), limit);
        var result = browse.execute(principalContext.requirePrincipal(), query);
        if (result.forbidden()) return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        var filters = new LinkedHashMap<String, Object>();
        filters.put("q", query.search()); filters.put("type", query.entityType()); filters.put("lifecycle", lifecycle);
        filters.put("after", after == null ? null : query.after().value().toString()); filters.put("limit", limit);
        var body = new LinkedHashMap<String, Object>(); body.put("storage", storage); body.put("query", filters);
        body.put("items", result.items().stream().map(EntityController::body).toList());
        body.put("nextCursor", result.nextCursor() == null ? null : result.nextCursor().value().toString());
        return ResponseEntity.ok(body);
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
