package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.telemetry.application.QueryMetricSeriesUseCase;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class MetricSeriesController {
    private final PrincipalContext principals;
    private final QueryMetricSeriesUseCase series;

    public MetricSeriesController(PrincipalContext principals, QueryMetricSeriesUseCase series) {
        this.principals = principals;
        this.series = series;
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalidParameter() {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_METRIC_QUERY"));
    }

    @GetMapping("/api/v1/entities/{entityId}/metrics/{metricKey}/series")
    public ResponseEntity<?> read(@PathVariable String entityId, @PathVariable String metricKey,
                                 @RequestParam long from, @RequestParam long till,
                                 @RequestParam(defaultValue = "500") int maxPoints) {
        EntityId entity;
        try { entity = EntityId.parse(entityId); }
        catch (RuntimeException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_METRIC_QUERY"));
        }
        var result = series.query(principals.requirePrincipal(), entity, metricKey, from, till, maxPoints);
        return switch (result.kind()) {
            case FORBIDDEN -> ResponseEntity.status(403).body(Map.of("error", result.error()));
            case NOT_FOUND -> ResponseEntity.status(404).body(Map.of("error", result.error()));
            case INVALID -> ResponseEntity.badRequest().body(Map.of("error", result.error()));
            case UNAVAILABLE -> ResponseEntity.status(503).body(Map.of("error", result.error()));
            case AVAILABLE -> ResponseEntity.ok().header("Cache-Control", "no-store").body(body(result));
        };
    }

    private static Map<String, Object> body(QueryMetricSeriesUseCase.Result result) {
        MetricSeriesResult page = result.page();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("kind", page.status().kind().name());
        status.put("fresh", page.status().fresh());
        status.put("lastPointAt", page.status().lastPointAtMillis());
        status.put("partial", page.status().partial());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entityId", page.entityId().value().toString());
        body.put("metricKey", page.metricKey());
        body.put("unit", result.unit());
        body.put("from", page.from());
        body.put("till", page.till());
        body.put("series", page.series().stream().map(series -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceInstanceId", series.sourceInstanceId());
            row.put("dataMode", series.dataMode());
            row.put("externalItemId", series.externalItemId());
            row.put("mappingRevision", series.mappingRevision());
            row.put("unit", series.unit());
            row.put("dimensions", series.dimensions());
            row.put("points", series.points().stream()
                .map(point -> new Object[] {point.timestampMillis(), point.value().toPlainString()})
                .toList());
            row.put("counterRates", series.counterRates().stream().map(rate -> {
                Map<String, Object> derived = new LinkedHashMap<>();
                derived.put("t", rate.timestampMillis());
                derived.put("rate", rate.perSecond().toPlainString());
                derived.put("counterReset", rate.counterReset());
                return derived;
            }).toList());
            return row;
        }).toList());
        body.put("status", status);
        body.put("derivation", page.derivation() == null ? null : Map.of(
            "kind", page.derivation(),
            "resetPolicy", com.acme.opsweave.telemetry.domain.CounterRatePolicy.RESET_FROM_ZERO
        ));
        return body;
    }
}
