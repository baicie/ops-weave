package com.acme.opsweave.platform.integration;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.ReadZabbixProblemsUseCase;
import com.acme.opsweave.integration.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/v1/integrations/zabbix/problems")
public class ZabbixProblemController {
    private final PrincipalContext principal;
    private final ReadZabbixProblemsUseCase reader;
    private final com.acme.opsweave.integration.application.IngestZabbixProblemsUseCase ingest;
    private final String storage;
    public ZabbixProblemController(PrincipalContext principal, ReadZabbixProblemsUseCase reader,
            com.acme.opsweave.integration.application.IngestZabbixProblemsUseCase ingest, com.acme.opsweave.platform.persistence.InventoryWiring wiring) {
        this.principal = principal; this.reader = reader; this.ingest = ingest; storage = wiring.label();
    }
    @PostMapping(path = "/ingest", consumes = "application/json")
    public Object ingest(HttpServletRequest request) {
        var body = com.acme.opsweave.platform.incident.IncidentController.object(request, Set.of("from", "till", "afterEventId", "limit"));
        if (!body.get("from").isIntegralNumber() || !body.get("till").isIntegralNumber() || !body.get("limit").isIntegralNumber()
            || !body.get("from").canConvertToLong() || !body.get("till").canConvertToLong() || !body.get("limit").canConvertToInt()
            || !(body.get("afterEventId").isNull() || body.get("afterEventId").isString())) throw new IllegalArgumentException();
        var result = ingest.execute(principal.requirePrincipal(), new ProblemReadWindow(body.get("from").asLong(), body.get("till").asLong(),
            body.get("afterEventId").isNull() ? null : body.get("afterEventId").asString(), Math.toIntExact(body.get("limit").asLong())));
        var response = new LinkedHashMap<String,Object>(); response.put("storage", storage); response.put("dataMode", result.dataMode());
        response.put("sourceInstanceId", result.sourceInstanceId()); response.put("accepted", result.saved().accepted());
        response.put("createdIncidents", result.saved().createdIncidents()); response.put("changedIncidents", result.saved().changedIncidents());
        response.put("unmappedHosts", result.saved().unmappedHosts()); response.put("nextAfterEventId", result.nextAfterEventId()); return response;
    }
    @GetMapping
    public Object read(@RequestParam long from, @RequestParam long till, @RequestParam(required = false) String afterEventId,
            @RequestParam(defaultValue = "25") int limit, HttpServletRequest request) {
        if (!Set.of("from", "till", "afterEventId", "limit").containsAll(request.getParameterMap().keySet())
            || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) throw new IllegalArgumentException();
        var result = reader.execute(principal.requirePrincipal(), new ProblemReadWindow(from, till, afterEventId, limit));
        var body = new LinkedHashMap<String,Object>();
        body.put("schemaVersion", "1.0"); body.put("storage", "not-persisted"); body.put("dataMode", result.dataMode());
        body.put("sourceInstanceId", result.sourceInstanceId()); body.put("sourceContract", "zabbix-7.0-event-v1");
        var query = new LinkedHashMap<String,Object>(); query.put("from", from); query.put("till", till); query.put("afterEventId", afterEventId); query.put("limit", limit);
        body.put("query", query); body.put("items", result.page().items().stream().map(ZabbixProblemController::body).toList());
        body.put("nextAfterEventId", result.page().nextAfterEventId()); return body;
    }
    private static Map<String,Object> body(ExternalProblem p) {
        var row = new LinkedHashMap<String,Object>(); row.put("schemaVersion", "1.0"); row.put("tenantId", p.tenantId().value());
        row.put("sourceInstanceId", p.sourceInstanceId()); row.put("problemEventId", p.problemEventId()); row.put("triggerId", p.triggerId());
        row.put("title", p.title()); row.put("severity", p.severity()); row.put("occurredAt", p.occurredAt().toString());
        row.put("observedAt", p.observedAt().toString()); row.put("hostIds", p.hostIds()); row.put("suppressed", p.suppressed());
        row.put("recoveryEventId", p.recoveryEventId()); row.put("recoveredAt", p.recoveredAt() == null ? null : p.recoveredAt().toString()); row.put("state", p.state().name());
        row.put("gaps", p.state() == ExternalProblem.State.RECOVERY_UNKNOWN ? List.of("RECOVERY_EVENT_UNAVAILABLE") : List.of());
        return row;
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST")); }
    @ExceptionHandler(ProblemReadException.class)
    ResponseEntity<?> source(ProblemReadException error) { return ResponseEntity.status(error.code() == ProblemReadException.Code.FORBIDDEN ? 403 : 503).body(Map.of("error", error.code().name())); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("error", "SOURCE_UNAVAILABLE")); }
}
