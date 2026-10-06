package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.WorkflowQualityService;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/workflows/quality/workflows/{id}/versions/{revision}")
public final class WorkflowQualityController {
    private final PrincipalContext principals;private final WorkflowQualityService service;private final com.acme.opsweave.integration.application.WorkflowDiagnosticService diagnostics;
    public WorkflowQualityController(PrincipalContext principals,InventoryWiring wiring,WorkflowController workflows){this.diagnostics=new com.acme.opsweave.integration.application.WorkflowDiagnosticService(wiring.workflows(),workflows.service(),Clock.systemUTC());this.principals=principals;this.service=new WorkflowQualityService(wiring.workflows(),workflows.service(),Clock.systemUTC());}
    private void request(HttpServletRequest r){if(r.getQueryString()!=null)throw new IllegalArgumentException();}
    @GetMapping public Object report(@PathVariable String id,@PathVariable int revision,HttpServletRequest r){request(r);return service.report(principals.requirePrincipal(),id,revision);}
    @GetMapping("/batches/{batchId}")public Object batch(@PathVariable String id,@PathVariable int revision,@PathVariable String batchId,HttpServletRequest r){request(r);if(!batchId.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalArgumentException();return service.batch(principals.requirePrincipal(),id,revision,UUID.fromString(batchId));}
    @GetMapping("/diagnostics")public Object diagnostics(@PathVariable String id,@PathVariable int revision,HttpServletRequest r){request(r);return WorkflowDiagnosticsJson.wire(diagnostics.report(principals.requirePrincipal(),id,revision));}
    @GetMapping("/diagnostics/{observationId}")public Object observation(@PathVariable String id,@PathVariable int revision,@PathVariable String observationId,HttpServletRequest r){request(r);if(!observationId.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalArgumentException();return WorkflowDiagnosticsJson.wire(diagnostics.observation(principals.requirePrincipal(),id,revision,UUID.fromString(observationId)));}
    // Keep unsupported methods at this trusted boundary instead of dispatching to a separate error path.
    @RequestMapping(path={"","/batches/{batchId}","/diagnostics","/diagnostics/{observationId}"},method={RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE})
    public ResponseEntity<?> rejectMutation(HttpServletRequest r){request(r);principals.requirePrincipal();return ResponseEntity.status(405).header("Allow","GET").body(Map.of("error","METHOD_NOT_ALLOWED"));}
}
