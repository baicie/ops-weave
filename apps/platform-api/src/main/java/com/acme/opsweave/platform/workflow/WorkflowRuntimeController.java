package com.acme.opsweave.platform.workflow;
import com.acme.opsweave.identity.api.*;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.application.WorkflowRuntimeService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.platform.identity.WorkflowBackgroundAuthorities;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.scheduling.annotation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

/** Dev and OIDC execution remain separate; delegation metadata cannot replace a trusted identity. */
@RestController
@EnableScheduling
@RequestMapping("/api/v1/integrations/workflows/runtime")
public class WorkflowRuntimeController {
 private final PrincipalContext principals;private final PrincipalResolver resolver;private final OpsweaveProperties properties;private final InventoryWiring wiring;private final WorkflowRuntimeService service;private final WorkflowBackgroundAuthorities background;
 public WorkflowRuntimeController(PrincipalContext principals,PrincipalResolver resolver,OpsweaveProperties properties,InventoryWiring wiring,WorkflowController workflows,ObjectProvider<WorkflowBackgroundAuthorities> background){this.principals=principals;this.resolver=resolver;this.properties=properties;this.wiring=wiring;this.service=workflows.runtime();this.background=background.getIfAvailable();}
 private boolean enabled(){return "postgres".equals(wiring.label())&&("dev".equals(properties.auth().mode())&&properties.auth().bindLoopbackOnly()||"oidc".equals(properties.auth().mode())&&background!=null);}
 private Principal principal(){var p=principals.requirePrincipal();if(!enabled())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);return p;}
 private static UUID uuid(String value){var id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}
 @GetMapping public Object status(HttpServletRequest request){if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principal();return Map.of("schemaVersion","2.0","mode",background==null?"LOCAL_DEV_ENTITY":"DELEGATED_ENTITY","backgroundAvailable",background==null||background.allowed(request,p),"pollSeconds",5,"maxBatchRecords",5,"tasks",service.tasks(p).stream().map(WorkflowRuntimeJson::wire).toList(),"executions",service.executions(p));}
 @GetMapping("/host-scans/{id}")public Object hostScan(@PathVariable String id,HttpServletRequest request){if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principal();return Map.of("schemaVersion","2.0","checkpoint",WorkflowHostScanJson.wire(service.hostCheckpoint(p,id).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND))),"batches",service.hostBatches(p,id).stream().map(WorkflowHostScanJson::wire).toList());}
 @GetMapping("/commands/{requestId}")public Object command(@PathVariable String requestId,HttpServletRequest request){if(request.getQueryString()!=null)throw new IllegalArgumentException();return WorkflowRuntimeJson.wire(service.controlReceipt(principal(),uuid(requestId)));}
 @PostMapping(value="/execute",consumes="application/json")public Object execute(HttpServletRequest request)throws IOException{if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principal();var n=CatalogJson.read(request);fields(n,Set.of("id","revision","digest","settings","previewId","samples","syncRunId"));UUID syncRunId=n.has("syncRunId")&&!n.get("syncRunId").isNull()?uuid(text(n,"syncRunId")):null;return service.execute(p,text(n,"id"),integer(n,"revision",null),text(n,"digest"),WorkflowRuntimeJson.settings(n.get("settings")),uuid(text(n,"previewId")),n.has("samples")?WorkflowJson.samples(n.get("samples")):null,syncRunId);}
 @PostMapping(value="/{operation:start|stop|resume}",consumes="application/json")public Object control(@PathVariable String operation,HttpServletRequest request)throws IOException{if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principal();var n=CatalogJson.read(request);fields(n,Set.of("requestId","id","revision","digest","settings","expectedGeneration"));var command=new WorkflowRuntimeControl.Command(uuid(text(n,"requestId")),text(n,"id"),integer(n,"revision",null),text(n,"digest"),WorkflowRuntimeJson.settings(n.get("settings")),integer(n,"expectedGeneration",null),WorkflowRuntimeControl.Operation.valueOf(operation.toUpperCase(java.util.Locale.ROOT)));return WorkflowRuntimeJson.wire(service.command(p,command,()->background==null?null:background.issue(request,p)));}
 @Scheduled(fixedDelay=5000)public void poll(){if(!enabled())return;try{if(background!=null){background.poll(service);return;}var p=resolver.resolve(new BearerCredentials(properties.auth().dev().token()));if(p.isPresent())service.tick(p.get());}catch(RuntimeException failure){/* No credential or identity is logged. Every subsequent write must reauthorize. */}}
}
