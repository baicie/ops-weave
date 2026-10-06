package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.WorkflowSampleRecoveryService;
import com.acme.opsweave.integration.domain.WorkflowSampleRecovery;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.time.Clock;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/integrations/workflows/sample-recovery")
public final class WorkflowSampleRecoveryController {
    private final PrincipalContext principals;private final WorkflowSampleRecoveryService service;
    public WorkflowSampleRecoveryController(PrincipalContext principals,InventoryWiring wiring){this.principals=principals;service=new WorkflowSampleRecoveryService(wiring.workflows(),Clock.systemUTC());}
    private void request(HttpServletRequest r){if(r.getQueryString()!=null)throw new IllegalArgumentException();}
    @PostMapping(value="/abandon",consumes="application/json")public Object abandon(@RequestBody String body,HttpServletRequest r){request(r);return service.abandon(principals.requirePrincipal(),WorkflowSampleRecoveryJson.command(body));}
    @GetMapping("/commands/{id}")public Object receipt(@PathVariable String id,HttpServletRequest r){request(r);return service.receipt(principals.requirePrincipal(),WorkflowRecoveryJson.canonical(id));}
    @GetMapping("/batches/{kind}/{id}")public Object status(@PathVariable String kind,@PathVariable String id,HttpServletRequest r){request(r);return service.status(principals.requirePrincipal(),WorkflowSampleRecovery.Kind.valueOf(kind),WorkflowRecoveryJson.canonical(id));}
    @RequestMapping(path={"/commands/{id}","/batches/{kind}/{id}"},method={RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE})public ResponseEntity<?> reject(HttpServletRequest r){request(r);principals.requirePrincipal();return ResponseEntity.status(405).header("Allow","GET").body(Map.of("error","METHOD_NOT_ALLOWED"));}
}
