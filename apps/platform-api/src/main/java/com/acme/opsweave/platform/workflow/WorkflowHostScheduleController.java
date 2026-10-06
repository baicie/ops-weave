package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.*;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.application.WorkflowHostScheduleService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.platform.identity.WorkflowBackgroundAuthorities;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v1/integrations/workflows/host-schedules")
public final class WorkflowHostScheduleController {
    private final PrincipalContext principals;private final PrincipalResolver resolver;private final OpsweaveProperties properties;private final InventoryWiring wiring;private final WorkflowHostScheduleService service;private final WorkflowBackgroundAuthorities background;
    public WorkflowHostScheduleController(PrincipalContext principals,PrincipalResolver resolver,OpsweaveProperties properties,InventoryWiring wiring,WorkflowController workflows,ObjectProvider<WorkflowBackgroundAuthorities> background){this.principals=principals;this.resolver=resolver;this.properties=properties;this.wiring=wiring;this.service=new WorkflowHostScheduleService(wiring.workflows(),workflows.runtime(),Clock.systemUTC());this.background=background.getIfAvailable();}
    private boolean enabled(){return "postgres".equals(wiring.label())&&("dev".equals(properties.auth().mode())&&properties.auth().bindLoopbackOnly()||"oidc".equals(properties.auth().mode())&&background!=null);}
    private Principal principal(HttpServletRequest request){if(request.getQueryString()!=null)throw new IllegalArgumentException();if(!enabled())throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);return principals.requirePrincipal();}
    @GetMapping("/workflows/{id}")public Object status(@PathVariable String id,HttpServletRequest request){var p=principal(request);var value=service.status(p,id);var n=CatalogJson.JSON.createObjectNode();n.put("schemaVersion","2.0");n.put("available",background==null||background.allowed(request,p));n.put("mode",background==null?"LOCAL_DEV_ENTITY":"DELEGATED_ENTITY");n.put("pollSeconds",5);n.put("maxSessionBatches",20);n.set("schedule",CatalogJson.JSON.valueToTree(value.schedule()));n.set("task",CatalogJson.JSON.valueToTree(value.task()==null?null:WorkflowRuntimeJson.wire(value.task())));return n;}
    @GetMapping("/commands/{id}")public Object receipt(@PathVariable UUID id,HttpServletRequest request){return service.receipt(principal(request),id);}
    @PostMapping(value="/{operation:start|stop|resume}",consumes="application/json")public Object command(@PathVariable String operation,HttpServletRequest request)throws IOException{var p=principal(request);var n=CatalogJson.read(request);fields(n,Set.of("requestId","id","revision","digest","settings","intervalSeconds","expectedGeneration"));if(n.size()!=7)throw new IllegalArgumentException();var c=new WorkflowHostSchedule.Command(UUID.fromString(text(n,"requestId")),text(n,"id"),integer(n,"revision",null),text(n,"digest"),WorkflowRuntimeJson.settings(n.get("settings")),integer(n,"intervalSeconds",null),integer(n,"expectedGeneration",null),WorkflowHostSchedule.Operation.valueOf(operation.toUpperCase(Locale.ROOT)));return service.command(p,c,()->background==null?null:background.issue(request,p));}
    @Scheduled(fixedDelay=5000)public void poll(){if(!enabled())return;try{if(background!=null){background.pollHosts(service);return;}resolver.resolve(new BearerCredentials(properties.auth().dev().token())).ifPresent(service::tick);}catch(RuntimeException unavailable){/* Each scan and output rechecks trusted identity and budget. */}}
}
