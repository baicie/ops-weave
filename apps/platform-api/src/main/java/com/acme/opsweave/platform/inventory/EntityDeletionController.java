package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.inventory.application.EntityDeletionService;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.EntityId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

/**
 * The operational delete entry for one clearable object. Reasoned, idempotent, tenant-level
 * {@code entity.manage}, and it never deletes a protected or unfinished record: an entity is only ever
 * soft deleted so old Incident/Evidence references keep resolving.
 */
@RestController
public final class EntityDeletionController {
    private final PrincipalContext principals;
    private final AuthorizationService authorization;
    private final InventoryWiring wiring;

    public EntityDeletionController(PrincipalContext principals, AuthorizationService authorization, InventoryWiring wiring) {
        this.principals = principals;
        this.authorization = authorization;
        this.wiring = wiring;
    }

    /**
     * Resolved per request: the store accessor refuses the memory build explicitly, so the refusal is
     * the request's failure — never a startup failure of the whole application context.
     */
    private EntityDeletionService service() {
        return new EntityDeletionService(authorization, wiring.query(), wiring.entityDeletions(), Clock.systemUTC());
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
    }

    private static UUID uuid(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }

    @PostMapping(value = "/api/v1/entities/{entityId}/deletions", consumes = "application/json")
    public Object delete(@PathVariable String entityId, HttpServletRequest request) throws IOException {
        noQuery(request);
        var n = CatalogJson.read(request);
        var command = EntityDeletionJson.command(n);
        if (!command.entityId().equals(new EntityId(uuid(entityId)))) throw new IllegalArgumentException();
        return EntityDeletionJson.wire(service().delete(principals.requirePrincipal(), command).receipt());
    }

    @GetMapping("/api/v1/entities/deletions/commands/{requestId}")
    public Object command(@PathVariable String requestId, HttpServletRequest request) {
        noQuery(request);
        return EntityDeletionJson.wire(service().receipt(principals.requirePrincipal(), uuid(requestId)));
    }
}
