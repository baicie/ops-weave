package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.inventory.api.ExternalLinkRegistrationStore;
import com.acme.opsweave.inventory.application.ExternalLinkRegistrationService;
import com.acme.opsweave.inventory.domain.ExternalLinkRegistration;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.EntityId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Controlled external-link registration and authorized read. Identity and tenant always come from
 *  the trusted principal; the request never supplies them and never resolves a name or address. */
@RestController
@RequestMapping("/api/v1/external-links")
public final class ExternalLinkController {
    private final PrincipalContext principals;
    private final ExternalLinkRegistrationService service;
    private final String storage;

    public ExternalLinkController(PrincipalContext principals, AuthorizationService authorization, InventoryWiring wiring) {
        this.principals = principals;
        this.storage = wiring.label();
        this.service = new ExternalLinkRegistrationService(authorization, wiring.query(), wiring.externalLinkRegistrations(), Clock.systemUTC());
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> register(HttpServletRequest request) throws IOException {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException("Invalid external link request");
        var p = principals.requirePrincipal();
        var n = PipelineJson.read(request, false);
        PipelineJson.fields(n, Set.of("requestId", "namespace", "objectType", "externalKey", "targetId", "generation", "reason"));
        var requestId = parseUuid(PipelineJson.text(n, "requestId"));
        var targetId = EntityId.parse(PipelineJson.text(n, "targetId"));
        String generation = n.has("generation") && !n.get("generation").isNull() ? PipelineJson.text(n, "generation") : null;
        String reason = n.has("reason") && !n.get("reason").isNull() ? PipelineJson.text(n, "reason") : null;
        var result = service.register(p, requestId, PipelineJson.text(n, "namespace"), PipelineJson.text(n, "objectType"),
            PipelineJson.text(n, "externalKey"), generation, targetId, reason);
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "1.0");
        body.put("requestId", requestId.toString());
        body.put("replayed", result.replayed());
        body.put("link", link(result.entry().registration()));
        return ResponseEntity.ok(body);
    }

    @GetMapping
    public ResponseEntity<?> page(@RequestParam(required = false) String namespace, @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "25") int limit, HttpServletRequest request) {
        if (!Set.of("namespace", "after", "limit").containsAll(request.getParameterMap().keySet())
            || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1) || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Invalid external link parameters");
        }
        var p = principals.requirePrincipal();
        UUID cursor = after == null ? null : parseUuid(after);
        var rows = service.page(p, namespace, cursor, limit);
        var items = rows.stream().limit(limit).map(ExternalLinkController::entry).toList();
        Object next = rows.size() > limit ? rows.get(limit - 1).registration().requestId().toString() : null;
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "1.0");
        body.put("storage", storage);
        body.put("tenantId", p.tenantId().value());
        body.put("items", items);
        body.put("nextCursor", next);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> link(ExternalLinkRegistration registration) {
        var key = registration.key();
        var m = new LinkedHashMap<String, Object>();
        m.put("namespace", key.sourceInstanceId());
        m.put("objectType", key.externalType());
        m.put("externalKey", key.externalId());
        m.put("generation", key.generation());
        m.put("targetId", registration.targetId().value().toString());
        return m;
    }

    private static Map<String, Object> entry(ExternalLinkRegistrationStore.Entry row) {
        var m = new LinkedHashMap<String, Object>();
        m.put("link", link(row.registration()));
        m.put("reason", row.registration().reason());
        m.put("registeredAt", row.registeredAt().toString());
        return m;
    }

    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static UUID parseUuid(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new IllegalArgumentException("Invalid external link id");
        return UUID.fromString(value);
    }
}
