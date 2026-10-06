package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.*;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.platform.identity.WorkflowBackgroundAuthorities;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v1/integrations/workflows/log-streams")
public final class WorkflowLogStreamController {
    private final PrincipalContext principals;private final PrincipalResolver resolver;private final OpsweaveProperties properties;private final InventoryWiring wiring;private final WorkflowLogOutputController output;private final WorkflowBackgroundAuthorities background;private final WorkflowLogStreamService service;
    public WorkflowLogStreamController(PrincipalContext principals,PrincipalResolver resolver,OpsweaveProperties properties,InventoryWiring wiring,WorkflowController workflows,SourceConnectionWiring connections,RegisteredHostSourceReader registered,WorkflowLogOutputController output,WorkflowMetricOutputController metrics,ObjectProvider<WorkflowBackgroundAuthorities> background){
        this.principals=principals;this.resolver=resolver;this.properties=properties;this.wiring=wiring;this.output=output;this.background=background.getIfAvailable();
        service=new WorkflowLogStreamService(wiring.workflows(),workflows.service(),(p,source,from,till)->{var c=connections.service().workflowConfiguration(p,source);return registered.logWindow(p,source,c.endpoint().pin(),c.credentialPin(),c.hostGroupIds(),from,till);},output.windowSink(),metrics.budget(),Clock.systemUTC());
    }
    private boolean enabled(){return output.configured()&&"postgres".equals(wiring.label())&&("dev".equals(properties.auth().mode())&&properties.auth().bindLoopbackOnly()||"oidc".equals(properties.auth().mode())&&background!=null);}
    private Principal principal(HttpServletRequest request){return principal(request,false);}
    private Principal principal(HttpServletRequest request,boolean versionQuery){if(!versionQuery&&request.getQueryString()!=null)throw new IllegalArgumentException();if(!enabled())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);return principals.requirePrincipal();}
    private Object wire(Principal p,String id,HttpServletRequest request,WorkflowLogStreamService.Status status){var n=CatalogJson.JSON.createObjectNode();n.put("schemaVersion","2.0");n.put("available",background==null||background.allowedMetric(request,p));n.put("mode",background==null?"LOCAL_DEV_LOG":"DELEGATED_LOG");n.put("windowSeconds",60);n.put("settleSeconds",10);n.put("maxBatchRecords",WorkflowLogWindow.MAX_RECORDS);n.put("maxSessionBatches",20);n.put("maxHistoryRequests",WorkflowLogWindow.MAX_HISTORY_REQUESTS);n.put("lookbackSeconds",WorkflowLogStream.LOOKBACK_SECONDS);n.set("task",CatalogJson.JSON.valueToTree(WorkflowLogStreamJson.wire(status.task())));n.set("batches",CatalogJson.JSON.valueToTree(status.batches()));if(status.control()!=null)n.set("control",CatalogJson.JSON.valueToTree(status.control()));if(status.resumeAllowed()!=null)n.put("resumeAllowed",status.resumeAllowed());if(status.uncertainBatchIds()!=null)n.set("uncertainBatchIds",CatalogJson.JSON.valueToTree(status.uncertainBatchIds()));return n;}
    @GetMapping("/workflows/{id}")public Object status(@PathVariable String id,HttpServletRequest request){var query=request.getQueryString();if(query==null){var p=principal(request);return wire(p,id,request,service.status(p,id));}if(!query.matches("revision=[1-9][0-9]{0,4}"))throw new IllegalArgumentException();var p=principal(request,true);return wire(p,id,request,service.versionStatus(p,id,Integer.parseInt(query.substring(9))));}
    private static UUID uuid(String value){var id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}
    @GetMapping("/commands/{id}")public Object receipt(@PathVariable String id,HttpServletRequest request){return WorkflowLogStreamJson.wire(service.receipt(principal(request),uuid(id)));}
    @GetMapping("/workflows/{id}/batches/{batchId}/records")public Object data(@PathVariable String id,@PathVariable String batchId,HttpServletRequest request){WorkflowDefinition.ref(id,1);return service.data(principal(request),id,uuid(batchId));}
    @GetMapping("/workflows/{id}/batches/{batchId}/records/after/{afterIndex}")public Object dataPage(@PathVariable String id,@PathVariable String batchId,@PathVariable int afterIndex,HttpServletRequest request){WorkflowDefinition.ref(id,1);if(afterIndex<0)throw new IllegalArgumentException();return service.data(principal(request),id,uuid(batchId),afterIndex);}
    @PostMapping(value="/{operation:start|stop|resume}",consumes="application/json")public Object command(@PathVariable String operation,HttpServletRequest request)throws IOException{var p=principal(request);var n=CatalogJson.read(request);fields(n,Set.of("requestId","id","revision","digest","expectedGeneration"));var command=new WorkflowLogStream.Command(SourceConnectionJson.uuid(n,"requestId"),text(n,"id"),integer(n,"revision",null),text(n,"digest"),integer(n,"expectedGeneration",null),WorkflowLogStream.Operation.valueOf(operation.toUpperCase(Locale.ROOT)));return WorkflowLogStreamJson.wire(service.command(p,command,()->background==null?null:background.issue(request,p)));}
    @PostMapping(value="/workflows/{id}/verification",consumes="application/json")public Object verify(@PathVariable String id,HttpServletRequest request)throws IOException{var p=principal(request);var n=CatalogJson.read(request);fields(n,Set.of("batchId"));if(n.size()!=1)throw new IllegalArgumentException();WorkflowDefinition.ref(id,1);var checked=service.verify(p,id,SourceConnectionJson.uuid(n,"batchId"));return wire(p,id,request,service.versionStatus(p,id,checked.task().revision()));}
    @Scheduled(fixedDelay=5000)public void poll(){if(!enabled())return;try{if(background!=null){background.pollLogs(service);return;}resolver.resolve(new BearerCredentials(properties.auth().dev().token())).ifPresent(service::tick);}catch(RuntimeException unavailable){/* Next batch rechecks current trusted identity; no credentials or payload are logged. */}}
}
