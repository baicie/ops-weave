package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.inventory.application.*;
import com.acme.opsweave.inventory.api.ObservationReader;
import com.acme.opsweave.inventory.domain.ObservationQuery;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.EntityId;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/entities/{entityId}/observations")
public final class ObservationController {
    private final PrincipalContext principals;
    private final ReadObservationsUseCase read;
    private final String storage;
    public ObservationController(PrincipalContext principals, GetEntityUseCase entities, InventoryWiring wiring) {
        this.principals = principals; this.storage = wiring.label();
        this.read = new ReadObservationsUseCase(entities, wiring.observations(), Clock.systemUTC());
    }
    @GetMapping public ResponseEntity<?> page(@PathVariable String entityId, @RequestParam long from, @RequestParam long till,
            @RequestParam(required = false) String asOf, @RequestParam(defaultValue = "") String source,
            @RequestParam(required = false) String after, @RequestParam(defaultValue = "25") int limit, HttpServletRequest request) {
        if (!entityId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
            || !Set.of("from", "till", "asOf", "source", "after", "limit").containsAll(request.getParameterMap().keySet())
            || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) throw new IllegalArgumentException("Invalid observation parameters");
        var principal = principals.requirePrincipal(); var id = EntityId.parse(entityId);
        Instant cutoff;
        try { cutoff = asOf == null ? Instant.now() : Instant.parse(asOf); }
        catch (DateTimeException invalid) { throw new IllegalArgumentException("Invalid observation cutoff"); }
        var query = new ObservationQuery(from, till, cutoff, source, after, limit);
        var page = read.execute(principal, id, query);
        if (page.kind() != EntityAccessKind.FOUND) return ResponseEntity.status(page.kind() == EntityAccessKind.FORBIDDEN ? 403 : 404).body(Map.of("error", page.kind().name()));
        var filters = new LinkedHashMap<String,Object>(); filters.put("from", from); filters.put("till", till); filters.put("asOf", query.asOf().toString());
        filters.put("source", source); filters.put("after", after); filters.put("limit", limit);
        var body = new LinkedHashMap<String,Object>(); body.put("storage", storage); body.put("tenantId", principal.tenantId().value()); body.put("entityId", id.value().toString());
        body.put("query", filters); body.put("items", page.items().stream().map(ObservationController::body).toList()); body.put("nextCursor", page.nextCursor());
        body.put("coverage", "retained-observations-only"); return ResponseEntity.ok(body);
    }
    static Map<String,Object> body(ObservationReader.Entry entry) {
        var o = entry.observation(); var result = new LinkedHashMap<String,Object>();
        result.put("schemaVersion", "1.0"); result.put("id", o.id()); result.put("tenantId", o.key().tenantId().value()); result.put("entityId", o.entityId().value().toString());
        result.put("sourceInstanceId", o.key().sourceInstanceId()); result.put("externalType", o.key().externalType()); result.put("externalId", o.key().externalId()); result.put("generation", o.key().generation());
        result.put("observedAt", o.observedAt().toString()); result.put("ingestedAt", o.ingestedAt().toString()); result.put("fields", o.fields());
        result.put("rawRecordRef", o.rawRecordRef()); result.put("mappingRevision", o.mappingRevision()); result.put("timePrecision", entry.exactTime() ? "nanoseconds" : "legacy-microseconds");
        var gaps = new ArrayList<String>();
        if (!o.fields().keySet().containsAll(Set.of("entityName", "entityType", "lifecycle"))) gaps.add("PROJECTION_FIELDS_UNAVAILABLE");
        if (!o.fields().containsKey("dataMode")) gaps.add("SOURCE_MODE_UNAVAILABLE");
        result.put("gaps", gaps); return result;
    }
}
