package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.telemetry.application.ListMetricDefinitionsUseCase;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricDefinitionController {
    private final PrincipalContext principalContext;
    private final ListMetricDefinitionsUseCase catalog;

    public MetricDefinitionController(PrincipalContext principalContext, ListMetricDefinitionsUseCase catalog) {
        this.principalContext = principalContext;
        this.catalog = catalog;
    }

    @GetMapping("/definitions")
    public ResponseEntity<?> definitions() {
        var result = catalog.list(principalContext.requirePrincipal());
        if (result.kind() == ListMetricDefinitionsUseCase.ListResult.Kind.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(Map.of("items", result.definitions().stream().map(MetricDefinitionController::definition).toList()));
    }

    @GetMapping("/bindings")
    public ResponseEntity<?> bindings() {
        var result = catalog.listBindings(principalContext.requirePrincipal());
        if (result.kind() == ListMetricDefinitionsUseCase.ListResult.Kind.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(Map.of("items", result.bindings().stream().map(MetricDefinitionController::binding).toList()));
    }

    private static Map<String, Object> definition(MetricDefinition definition) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", definition.tenantId().value());
        body.put("metricKey", definition.metricKey());
        body.put("displayName", definition.displayName());
        body.put("unit", definition.unit());
        body.put("valueType", definition.valueType().name());
        body.put("metricType", definition.metricType().name());
        body.put("dimensionSchema", definition.dimensionSchema());
        return body;
    }

    private static Map<String, Object> binding(MetricBinding binding) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", binding.tenantId().value());
        body.put("sourceType", binding.sourceType());
        body.put("sourceInstanceId", binding.sourceInstanceId());
        body.put("externalItemId", binding.externalItemId());
        body.put("entityId", binding.entityId().value().toString());
        body.put("hostExternalId", binding.hostExternalId());
        body.put("metricKey", binding.metricKey());
        body.put("fixedDimensions", binding.fixedDimensions());
        body.put("sourceUnit", binding.sourceUnit());
        body.put("valueTransform", binding.valueTransform());
        body.put("mappingRevision", binding.mappingRevision());
        body.put("lifecycle", binding.lifecycle().name());
        return body;
    }
}
