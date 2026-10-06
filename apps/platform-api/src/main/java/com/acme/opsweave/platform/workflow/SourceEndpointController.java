package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.SourceEndpointService;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/data-sources/endpoints")
public final class SourceEndpointController {
    private final PrincipalContext principals;private final SourceEndpointService service;private final InventoryWiring wiring;
    public SourceEndpointController(PrincipalContext principals,RegisteredSourceEndpoints endpoints,InventoryWiring wiring){this.principals=principals;service=new SourceEndpointService(endpoints);this.wiring=wiring;}
    private void query(HttpServletRequest r){if(!r.getParameterMap().isEmpty()||!r.getRequestURI().matches("/api/v2/data-sources/endpoints(?:/[a-z][a-z0-9_-]{0,63})?"))throw new IllegalArgumentException();}
    @GetMapping public Object list(HttpServletRequest r){query(r);return Map.of("schemaVersion","2.0","storage",wiring.label(),"items",service.list(principals.requirePrincipal()));}
    @GetMapping("/{id}")public Object read(@PathVariable String id,HttpServletRequest r){query(r);return Map.of("schemaVersion","2.0","storage",wiring.label(),"endpoint",service.read(principals.requirePrincipal(),id));}
}
