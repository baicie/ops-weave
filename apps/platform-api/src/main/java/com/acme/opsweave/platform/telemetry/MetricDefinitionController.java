package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.telemetry.application.ListMetricDefinitionsUseCase;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metrics/definitions")
public class MetricDefinitionController {
    private final PrincipalContext principalContext;
    private final ListMetricDefinitionsUseCase listDefinitions;

    public MetricDefinitionController(PrincipalContext principalContext, ListMetricDefinitionsUseCase listDefinitions) {
        this.principalContext = principalContext;
        this.listDefinitions = listDefinitions;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        var result = listDefinitions.list(principalContext.requirePrincipal());
        if (result.kind() == ListMetricDefinitionsUseCase.ListResult.Kind.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(Map.of("items", result.definitions().stream().map(MetricDefinitionController::body).toList()));
    }

    private static Map<String, Object> body(MetricDefinition definition) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        var external = definition.externalMapping();
        mapping.put("sourceType", external.sourceType());
        mapping.put("sourceInstanceId", external.sourceInstanceId());
        mapping.put("externalId", external.externalId());
        mapping.put("itemKey", external.itemKey());
        mapping.put("hostExternalId", external.hostExternalId());
        mapping.put("sourceUnit", external.sourceUnit());
        mapping.put("valueTransform", external.valueTransform());
        mapping.put("mappingRevision", external.mappingRevision());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metricId", definition.id());
        body.put("tenantId", definition.tenantId().value());
        body.put("name", definition.name());
        body.put("displayName", definition.displayName());
        body.put("entityType", definition.entityType());
        body.put("unit", definition.unit());
        body.put("valueType", definition.valueType().name());
        body.put("metricType", definition.metricType().name());
        body.put("dimensions", definition.dimensions());
        body.put("origin", definition.origin().wireValue());
        body.put("lifecycle", definition.lifecycle().name());
        body.put("externalMapping", mapping);
        return body;
    }
}
