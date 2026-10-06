package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.WorkflowQualityAlertService;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.time.Clock;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/integrations/workflows/quality/workflows/{id}/versions/{revision}/alerts")
public final class WorkflowQualityAlertsController {
    private final PrincipalContext principals;private final WorkflowQualityAlertService service;
    public WorkflowQualityAlertsController(PrincipalContext principals,InventoryWiring wiring,WorkflowController workflows){this.principals=principals;service=new WorkflowQualityAlertService(wiring.workflows(),workflows.service(),Clock.systemUTC());}
    private void request(HttpServletRequest r){if(r.getQueryString()!=null)throw new IllegalArgumentException();}
    @GetMapping public Object status(@PathVariable String id,@PathVariable int revision,HttpServletRequest r){request(r);return service.status(principals.requirePrincipal(),id,revision);}
    @GetMapping("/commands/{requestId}")public Object receipt(@PathVariable String id,@PathVariable int revision,@PathVariable String requestId,HttpServletRequest r){request(r);return service.receipt(principals.requirePrincipal(),id,revision,WorkflowRecoveryJson.canonical(requestId));}
    @PostMapping(value="/configure",consumes="application/json")public Object configure(@PathVariable String id,@PathVariable int revision,@RequestBody String body,HttpServletRequest r){request(r);var p=principals.requirePrincipal();var c=WorkflowQualityAlertsJson.command(body);if(!c.id().equals(id)||c.revision()!=revision)throw new IllegalArgumentException();return service.configure(p,c);}
    @RequestMapping(path={"","/commands/{requestId}"},method={RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE})public ResponseEntity<?> reject(HttpServletRequest r){request(r);principals.requirePrincipal();return ResponseEntity.status(405).header("Allow","GET").body(Map.of("error","METHOD_NOT_ALLOWED"));}
}
