package com.acme.opsweave.platform.catalog;

import com.acme.opsweave.catalog.application.ModelExtensionService;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

/**
 * Tenant-namespaced model extension overlays. The base model body is never modified here:
 * extensions only declare additional optional bounded scalar fields for one fixed base version.
 */
@RestController
@RequestMapping("/api/v1/catalog/extensions")
public class ModelExtensionController {
    private final PrincipalContext principals;
    private final InventoryWiring wiring;
    private final BuiltinCatalog builtins = new BuiltinCatalog();
    private final ModelExtensionService service;

    public ModelExtensionController(PrincipalContext principals, InventoryWiring wiring) {
        this.principals = principals;
        this.wiring = wiring;
        this.service = new ModelExtensionService(wiring.modelExtensions(), wiring.modelCatalog(), builtins.definitions(), java.time.Clock.systemUTC());
    }

    @GetMapping
    public Object list() {
        var p = principals.requirePrincipal();
        service.authorize(p, false);
        var published = service.published(p);
        var drafts = service.drafts(p);
        return Map.of("schemaVersion", "1.0", "storage", wiring.label(),
            "published", Map.of("items", published.items().stream().map(CatalogJson::wire).toList(), "truncated", published.truncated()),
            "drafts", Map.of("items", drafts.items().stream().map(CatalogJson::wire).toList(), "truncated", drafts.truncated()));
    }

    @PostMapping(value = "/drafts", consumes = "application/json")
    public Object save(HttpServletRequest request) throws IOException {
        var p = principals.requirePrincipal();
        service.authorize(p, true);
        var n = CatalogJson.read(request);
        fields(n, Set.of("base", "fields", "expectedEditVersion"));
        return CatalogJson.wire(service.save(p, CatalogJson.ref(n.get("base")), CatalogJson.extensionFields(n.get("fields")), integer(n, "expectedEditVersion", null)));
    }

    @PostMapping(value = "/publish", consumes = "application/json")
    public Object publish(HttpServletRequest request) throws IOException {
        var p = principals.requirePrincipal();
        service.authorize(p, true);
        var n = CatalogJson.read(request);
        fields(n, Set.of("base", "expectedEditVersion", "digest"));
        return CatalogJson.wire(service.publish(p, CatalogJson.ref(n.get("base")), integer(n, "expectedEditVersion", null), text(n, "digest")));
    }
}
