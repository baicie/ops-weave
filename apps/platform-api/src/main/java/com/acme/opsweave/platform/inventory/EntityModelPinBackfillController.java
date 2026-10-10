package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.catalog.api.ModelCatalogStore;
import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.catalog.domain.ModelPreview;
import com.acme.opsweave.identity.api.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.EntityInstanceStore;
import com.acme.opsweave.inventory.api.EntityModelPinBackfillStore;
import com.acme.opsweave.inventory.domain.EntityModelPin;
import com.acme.opsweave.platform.catalog.BuiltinCatalog;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Explicit, bounded and authorized legacy model-pin backfill. The command never accepts a tenant
 * or actor, never guesses model matches and never touches source-managed or already pinned rows.
 */
@RestController
@RequestMapping("/api/v1/entities/model-pins/backfill")
public final class EntityModelPinBackfillController {
    private final PrincipalContext principals;
    private final AuthorizationService authorization;
    private final InventoryWiring wiring;
    private final List<ModelDefinition> builtins = new BuiltinCatalog().definitions();
    private final Clock clock = Clock.systemUTC();

    public EntityModelPinBackfillController(PrincipalContext principals, AuthorizationService authorization, InventoryWiring wiring) {
        this.principals = principals;
        this.authorization = authorization;
        this.wiring = wiring;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> backfill(HttpServletRequest request) throws IOException {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException("Invalid backfill request");
        var p = principals.requirePrincipal();
        var n = PipelineJson.read(request, false);
        PipelineJson.fields(n, Set.of("requestId", "entityType", "model", "maxUpdates"));
        var requestId = parseUuid(PipelineJson.text(n, "requestId"));
        var entityType = PipelineJson.text(n, "entityType");
        if (!entityType.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid backfill entity type");
        var modelNode = n.get("model");
        PipelineJson.fields(modelNode, Set.of("id", "revision"));
        var ref = new ModelDefinition.Ref(PipelineJson.text(modelNode, "id"), PipelineJson.integer(modelNode, "revision", null));
        int maxUpdates = PipelineJson.integer(n, "maxUpdates", null);
        authorize(p);
        var model = resolve(p, ref);
        if (model.kind() != ModelDefinition.Kind.ENTITY) throw new IllegalArgumentException("Invalid backfill model");
        if (!entityType.equals(EntityInstanceController.entityType(model))) throw new IllegalArgumentException("Backfill entity type mismatch");
        var pin = new EntityModelPin(model.id(), model.revision(), model.digest());
        var receipt = wiring.entityModelPinBackfills().backfill(p.tenantId(), requestId, entityType, pin, maxUpdates,
            attributes -> {
                try { return ModelPreview.evaluate(model, attributes).valid(); }
                catch (RuntimeException indeterminable) { return false; }
            }, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return ResponseEntity.ok(wire(receipt, p));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<?> receipt(@PathVariable String requestId) {
        var p = principals.requirePrincipal();
        authorize(p);
        var stored = wiring.entityModelPinBackfills().receipt(p.tenantId(), parseUuid(requestId))
            .orElseThrow(() -> new EntityModelPinBackfillStore.NotFound("Unknown backfill request"));
        return ResponseEntity.ok(wire(stored, p));
    }

    /** Backfill rewrites legacy rows tenant-wide; object-scoped grants are not sufficient. */
    private void authorize(Principal p) {
        if (!p.has(com.acme.opsweave.identity.domain.Permission.ENTITY_MANAGE) || !p.resourceScope().isTenantWide()
            || authorization.authorize(p, ResourceRef.anyEntity(p.tenantId()), com.acme.opsweave.identity.domain.Permission.ENTITY_MANAGE).denied()) {
            throw new EntityInstanceStore.Access("Entity management denied");
        }
    }

    private ModelDefinition resolve(Principal p, ModelDefinition.Ref ref) {
        return builtins.stream().filter(definition -> definition.ref().equals(ref)).findFirst()
            .orElseGet(() -> wiring.modelCatalog().find(p.tenantId(), ref).map(ModelCatalogStore.Entry::definition)
                .orElseThrow(() -> new EntityModelPinBackfillStore.NotFound("Backfill model is not published")));
    }

    private Map<String, Object> wire(EntityModelPinBackfillStore.Receipt receipt, Principal p) {
        var pin = new LinkedHashMap<String, Object>();
        pin.put("id", receipt.model().id()); pin.put("revision", receipt.model().revision()); pin.put("digest", receipt.model().digest());
        var counts = new LinkedHashMap<String, Object>();
        counts.put("scanned", receipt.counts().scanned()); counts.put("updated", receipt.counts().updated());
        counts.put("keptWithoutPin", receipt.counts().keptWithoutPin()); counts.put("skippedPinned", receipt.counts().skippedPinned());
        counts.put("skippedSource", receipt.counts().skippedSource());
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "1.0"); body.put("storage", wiring.label()); body.put("replayed", receipt.replayed());
        body.put("tenantId", p.tenantId().value()); body.put("requestId", receipt.requestId().toString());
        body.put("request", Map.of("entityType", receipt.entityType(), "model", pin));
        body.put("counts", counts);
        body.put("completedAt", receipt.completedAt().toString());
        return body;
    }

    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static UUID parseUuid(String value) {
        if (!UUID_PATTERN.matcher(value).matches()) throw new IllegalArgumentException("Invalid UUID");
        return UUID.fromString(value);
    }
}
