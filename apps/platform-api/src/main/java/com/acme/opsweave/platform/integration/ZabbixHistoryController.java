package com.acme.opsweave.platform.integration;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.ReadZabbixHistoryUseCase;
import com.acme.opsweave.integration.domain.HistoryCursor;
import com.acme.opsweave.integration.domain.HistoryWindow;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ZabbixHistoryController {
    private final PrincipalContext principals;
    private final ReadZabbixHistoryUseCase history;

    public ZabbixHistoryController(PrincipalContext principals, ReadZabbixHistoryUseCase history) {
        this.principals = principals;
        this.history = history;
    }

    @GetMapping("/api/v1/integrations/zabbix/items/{itemId}/history")
    public ResponseEntity<?> read(@PathVariable String itemId, @RequestParam long from, @RequestParam long till,
                                 @RequestParam(required = false) Long afterClock, @RequestParam(required = false) Integer afterNs,
                                 @RequestParam(defaultValue = "100") int limit) {
        try {
            if ((afterClock == null) != (afterNs == null)) throw new IllegalArgumentException("Incomplete cursor");
            var window = new HistoryWindow(from, till, afterClock == null ? null : new HistoryCursor(afterClock, afterNs), limit);
            var result = history.execute(principals.requirePrincipal(), itemId, window);
            if (result.kind() != ReadZabbixHistoryUseCase.Kind.SUCCESS) {
                int status = switch (result.kind()) {
                    case FORBIDDEN -> 403;
                    case NOT_FOUND -> 404;
                    default -> 503;
                };
                return ResponseEntity.status(status).body(Map.of("error", result.error()));
            }
            var binding = result.binding();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dataMode", result.dataMode());
            body.put("persistence", "not-persisted");
            body.put("tenantId", binding.tenantId().value());
            body.put("sourceInstanceId", binding.sourceInstanceId());
            body.put("externalItemId", binding.externalItemId());
            body.put("entityId", binding.entityId().value().toString());
            body.put("metricKey", binding.metricKey());
            body.put("unit", result.definition().unit());
            body.put("dimensions", binding.fixedDimensions());
            body.put("mappingRevision", binding.mappingRevision());
            body.put("definitionVersion", result.definition().version());
            body.put("bindingVersion", binding.version());
            body.put("from", from);
            body.put("till", till);
            body.put("points", result.page().points().stream().map(point -> Map.of(
                "clock", point.timestamp().getEpochSecond(), "ns", point.timestamp().getNano(),
                "value", point.value().toPlainString())).toList());
            body.put("nextCursor", result.page().nextCursor());
            body.put("windowComplete", result.page().windowComplete());
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_HISTORY_REQUEST"));
        }
    }
}
