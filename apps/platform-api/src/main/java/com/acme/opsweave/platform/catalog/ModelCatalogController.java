package com.acme.opsweave.platform.catalog;

import com.acme.opsweave.catalog.application.ModelCatalogService;
import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v1/catalog")
public class ModelCatalogController {
    private final PrincipalContext principals; private final InventoryWiring wiring;
    private final BuiltinCatalog builtins = new BuiltinCatalog(); private final ModelCatalogService service;
    public ModelCatalogController(PrincipalContext principals, InventoryWiring wiring) {
        this.principals = principals; this.wiring = wiring; this.service = new ModelCatalogService(wiring.modelCatalog(), builtins.definitions(), java.time.Clock.systemUTC());
    }
    @GetMapping
    public Object list() {
        var p = principals.requirePrincipal(); service.authorize(p, false);
        var published = service.published(p); var drafts = service.drafts(p);
        return Map.of("schemaVersion", "1.0", "storage", wiring.label(), "package", builtins.bundle(),
            "published", Map.of("items", published.items().stream().map(CatalogJson::wire).toList(), "truncated", published.truncated()),
            "drafts", Map.of("items", drafts.items().stream().map(CatalogJson::wire).toList(), "truncated", drafts.truncated()));
    }
    @PostMapping(value = "/drafts", consumes = "application/json")
    public Object save(HttpServletRequest request) throws IOException {
        var p = principals.requirePrincipal(); service.authorize(p, true); var n = CatalogJson.read(request); fields(n, Set.of("definition", "expectedEditVersion"));
        return CatalogJson.wire(service.save(p, CatalogJson.definition(n.get("definition")), integer(n, "expectedEditVersion", null)));
    }
    @PostMapping(value = "/publish", consumes = "application/json")
    public Object publish(HttpServletRequest request) throws IOException {
        var p = principals.requirePrincipal(); service.authorize(p, true); var n = CatalogJson.read(request); fields(n, Set.of("ref", "expectedEditVersion", "digest"));
        return CatalogJson.wire(service.publish(p, CatalogJson.ref(n.get("ref")), integer(n, "expectedEditVersion", null), text(n, "digest")));
    }
    @PostMapping(value = "/preview", consumes = "application/json")
    public Object preview(HttpServletRequest request) throws IOException {
        var p = principals.requirePrincipal(); service.authorize(p, false); var n = CatalogJson.read(request); fields(n, Set.of("definition", "sample"));
        return service.preview(p, CatalogJson.definition(n.get("definition")), CatalogJson.sample(n.get("sample")));
    }
    @GetMapping("/versions/{id}/{revision}")
    public Object version(@PathVariable String id, @PathVariable int revision) { return CatalogJson.wire(service.find(principals.requirePrincipal(), new ModelDefinition.Ref(id, revision))); }
}
