package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.WorkflowRecoveryService;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.time.Clock;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/integrations/workflows/recovery")
public final class WorkflowRecoveryController {
    private final PrincipalContext principals;private final WorkflowRecoveryService service;
    public WorkflowRecoveryController(PrincipalContext principals,InventoryWiring wiring){this.principals=principals;service=new WorkflowRecoveryService(wiring.workflows(),Clock.systemUTC());}
    private void request(HttpServletRequest r){if(r.getQueryString()!=null)throw new IllegalArgumentException();}
    @PostMapping("/abandon")public Object abandon(@RequestBody String body,HttpServletRequest r){request(r);var p=principals.requirePrincipal();return service.abandon(p,WorkflowRecoveryJson.command(body));}
    @GetMapping("/commands/{id}")public Object receipt(@PathVariable String id,HttpServletRequest r){request(r);return service.receipt(principals.requirePrincipal(),WorkflowRecoveryJson.canonical(id));}
    @RequestMapping(path="/commands/{id}",method={RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE})
    public ResponseEntity<?> reject(HttpServletRequest r){request(r);principals.requirePrincipal();return ResponseEntity.status(405).header("Allow","GET").body(Map.of("error","METHOD_NOT_ALLOWED"));}
}
