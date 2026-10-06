package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.MetricMappingService;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v2/metric-bindings")
public final class MetricMappingController {
    private final PrincipalContext principals; private final MetricMappingService service;
    public MetricMappingController(PrincipalContext principals,InventoryWiring wiring) {
        this.principals=principals;service=new MetricMappingService(wiring.metricMappings(),ClasspathMappingCatalog.load(getClass().getClassLoader()),Clock.systemUTC());
    }
    private void closed(HttpServletRequest request) {
        if(!request.getParameterMap().isEmpty()||!request.getRequestURI().matches("/api/v2/metric-bindings(?:/[A-Za-z0-9][A-Za-z0-9_.-]{0,63}/[1-9][0-9]{0,19}(?:/mapping(?:/commands/[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})?)?)?"))throw new IllegalArgumentException();
    }
    private static Map<String,Object> view(MetricMappingService.View v) {
        return Map.of("binding",MetricMappingJson.binding(v.binding()),"canConfigure",v.canConfigure(),"candidates",v.candidates().stream().map(MetricMappingJson::definition).toList());
    }
    @GetMapping public Object list(HttpServletRequest request) {
        closed(request);var page=service.list(principals.requirePrincipal());return Map.of("schemaVersion","2.0","items",page.items().stream().map(MetricMappingController::view).toList(),"truncated",page.truncated());
    }
    @GetMapping("/{source}/{item}") public Object read(@PathVariable String source,@PathVariable String item,HttpServletRequest request) {
        closed(request);return Map.of("schemaVersion","2.0","view",view(service.read(principals.requirePrincipal(),source,item)));
    }
    @PostMapping(value="/{source}/{item}/mapping",consumes="application/json") public Object write(@PathVariable String source,@PathVariable String item,HttpServletRequest request)throws IOException {
        closed(request);var p=principals.requirePrincipal();var n=CatalogJson.read(request);fields(n,Set.of("requestId","expectedBindingVersion","mappingPin"));
        String raw=text(n,"requestId");var id=UUID.fromString(raw);if(!id.toString().equals(raw))throw new IllegalArgumentException();
        var pin=MetricMappingJson.pin(n.get("mappingPin"));if(pin==null)throw new IllegalArgumentException();
        var c=new MetricMappingService.Command(id,integer(n,"expectedBindingVersion",null),pin);
        return Map.of("schemaVersion","2.0","receipt",MetricMappingJson.receipt(service.write(p,source,item,c)));
    }
    @GetMapping("/{source}/{item}/mapping/commands/{requestId}") public Object receipt(@PathVariable String source,@PathVariable String item,@PathVariable UUID requestId,HttpServletRequest request) {
        closed(request);return Map.of("schemaVersion","2.0","receipt",MetricMappingJson.receipt(service.receipt(principals.requirePrincipal(),source,item,requestId)));
    }
}
