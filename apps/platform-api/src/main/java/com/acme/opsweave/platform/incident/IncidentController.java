package com.acme.opsweave.platform.incident;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.incident.domain.IncidentStatus;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/incidents")
public final class IncidentController {
    private final PrincipalContext principal;
    private final IncidentService service;
    private final String storage;
    public IncidentController(PrincipalContext principal, IncidentService service, InventoryWiring wiring) { this.principal = principal; this.service = service; storage = wiring.label(); }
    @GetMapping
    public Object page(@RequestParam(defaultValue = "") String status, @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "25") int limit, HttpServletRequest request) {
        if (!Set.of("status", "after", "limit").containsAll(request.getParameterMap().keySet())
            || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)) throw new IllegalArgumentException();
        var page = service.page(principal.requirePrincipal(), status.isEmpty() ? null : IncidentStatus.valueOf(status), after == null ? null : uuid(after), limit);
        var query = new LinkedHashMap<String,Object>(); query.put("status", status); query.put("after", after == null ? null : uuid(after).toString()); query.put("limit", limit);
        var result = new LinkedHashMap<String,Object>(); result.put("storage", storage); result.put("query", query);
        result.put("items", page.items().stream().map(IncidentJson::header).toList()); result.put("nextCursor", page.nextCursor() == null ? null : page.nextCursor().toString()); return result;
    }
    @GetMapping("/{incidentId}")
    public Object get(@PathVariable String incidentId, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException();
        return Map.of("storage", storage, "record", IncidentJson.body(service.get(principal.requirePrincipal(), uuid(incidentId))));
    }
    @PostMapping(path = "/{incidentId}/transitions", consumes = "application/json")
    public Object transition(@PathVariable String incidentId, HttpServletRequest request) {
        JsonNode body = object(request, Set.of("expectedVersion", "target", "requestKey"));
        if (!body.get("expectedVersion").isIntegralNumber() || !body.get("expectedVersion").canConvertToLong() || !body.get("target").isString() || !body.get("requestKey").isString()) throw new IllegalArgumentException();
        var result = service.transition(principal.requirePrincipal(), uuid(incidentId), body.get("expectedVersion").asLong(),
            IncidentStatus.valueOf(body.get("target").asString()), uuid(body.get("requestKey").asString()));
        return Map.of("incidentId", result.incidentId().toString(), "status", result.status().name(), "version", result.version(), "storage", storage);
    }
    public static JsonNode object(HttpServletRequest request, Set<String> fields) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException();
        try {
            byte[] bytes = request.getInputStream().readNBytes(2049); if (bytes.length == 0 || bytes.length > 2048) throw new IllegalArgumentException();
            var body = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes);
            if (!body.isObject() || body.size() != fields.size() || !fields.stream().allMatch(body::has)) throw new IllegalArgumentException(); return body;
        } catch (IOException | tools.jackson.core.JacksonException invalid) { throw new IllegalArgumentException("Invalid request body"); }
    }
    @PostMapping(path="/reorganizations", consumes="application/json")
    public Object reorganize(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException();
        try {
            byte[] body=request.getInputStream().readNBytes(16385); if (body.length==0 || body.length>16384) throw new IllegalArgumentException();
            var input=ReorganizationJson.request(body);
            return Map.of("storage",storage,"change",ReorganizationJson.body(service.reorganize(principal.requirePrincipal(),input)));
        } catch (IOException | tools.jackson.core.JacksonException invalid) { throw new IllegalArgumentException("Invalid reorganization request"); }
    }
    @GetMapping("/reorganizations/{requestKey}")
    public Object reorganization(@PathVariable String requestKey, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new IllegalArgumentException();
        return Map.of("storage",storage,"change",ReorganizationJson.body(service.reorganization(principal.requirePrincipal(),uuid(requestKey))));
    }
    @GetMapping("/{incidentId}/reorganizations")
    public Object reorganizations(@PathVariable String incidentId,@RequestParam(required=false) String after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){
        if(!Set.of("after","limit").containsAll(request.getParameterMap().keySet())||request.getParameterMap().values().stream().anyMatch(v->v.length!=1))throw new IllegalArgumentException();
        var id=uuid(incidentId);var cursor=after==null?null:uuid(after);var page=service.reorganizations(principal.requirePrincipal(),id,cursor,limit);
        var result=new LinkedHashMap<String,Object>();result.put("storage",storage);result.put("incidentId",id.toString());result.put("after",cursor==null?null:cursor.toString());result.put("limit",limit);
        result.put("items",page.items().stream().map(ReorganizationJson::body).toList());result.put("nextCursor",page.nextCursor()==null?null:page.nextCursor().toString());return result;
    }
    public static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException();
        return UUID.fromString(value);
    }
}
