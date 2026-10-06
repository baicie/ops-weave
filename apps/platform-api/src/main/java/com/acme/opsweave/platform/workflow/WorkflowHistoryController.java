package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.WorkflowHistoryService;
import com.acme.opsweave.integration.domain.WorkflowHistory;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.integration.EnvSecretSource;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.time.Clock;
import java.util.*;

@RestController
@RequestMapping("/api/v1/integrations/workflows/quality/workflows/{id}/versions/{revision}/history")
public final class WorkflowHistoryController {
    private final PrincipalContext principals;private final WorkflowHistoryService service;
    public WorkflowHistoryController(PrincipalContext principals,InventoryWiring wiring,WorkflowController workflows,OpsweaveProperties properties,EnvSecretSource secrets,@Value("${OPSWEAVE_HISTORY_CURSOR_KEY_REF:}") String keyRef){this.principals=principals;service=new WorkflowHistoryService(wiring.workflows(),workflows.service(),AesWorkflowHistoryCursors.configured(keyRef,"dev".equals(properties.auth().mode()),secrets),Clock.systemUTC());}
    private static void request(HttpServletRequest r){if(r.getQueryString()!=null){var parameters=r.getParameterMap();if(parameters.size()!=1||!parameters.containsKey("cursor")||parameters.get("cursor").length!=1)throw new IllegalArgumentException();}}
    public static Object wire(WorkflowHistory.Page page){var body=new LinkedHashMap<String,Object>();body.put("schemaVersion",page.schemaVersion());body.put("asOf",page.asOf());body.put("snapshotAt",page.snapshotAt());body.put("reference",page.reference());body.put("coverage",page.coverage());body.put("offset",page.offset());body.put("recordedCount",page.recordedCount());body.put("items",page.items().stream().map(WorkflowDiagnosticsJson::wire).toList());body.put("nextCursor",page.nextCursor());body.put("hasMore",page.hasMore());return body;}
    @GetMapping public Object history(@PathVariable String id,@PathVariable int revision,@RequestParam(required=false) String cursor,HttpServletRequest request){var principal=principals.requirePrincipal();request(request);return wire(service.history(principal,id,revision,cursor));}
    @RequestMapping(method={RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE})public ResponseEntity<?> reject(HttpServletRequest r){principals.requirePrincipal();request(r);return ResponseEntity.status(405).header("Allow","GET").body(Map.of("error","METHOD_NOT_ALLOWED"));}
}
