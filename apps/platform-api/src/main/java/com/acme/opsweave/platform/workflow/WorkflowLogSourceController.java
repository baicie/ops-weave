package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.WorkflowLogSourceService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/data-sources")
public final class WorkflowLogSourceController {
    private final PrincipalContext principals;private final SourceConnectionWiring connections;private final WorkflowLogSourceService service;
    public WorkflowLogSourceController(PrincipalContext principals,SourceConnectionWiring connections,InventoryWiring wiring){this.principals=principals;this.connections=connections;service=new WorkflowLogSourceService(wiring.workflows(),connections.service(),Clock.systemUTC());}
    @GetMapping("/{id}/connection/{revision}/workflow-logs")public Object list(@PathVariable UUID id,@PathVariable int revision,HttpServletRequest request){
        if(request.getQueryString()!=null||!request.getRequestURI().matches("/api/v2/data-sources/[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}/connection/[1-9][0-9]{0,2}/workflow-logs"))throw new IllegalArgumentException();
        var p=principals.requirePrincipal();var c=connections.service().configuration(p,id,revision).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE));var instance=connections.service().read(p,id).instance();
        var source=new WorkflowDefinition.Source("ZABBIX_HOST",instance.source().instanceId(),new WorkflowDefinition.ConfigurationPin(id,revision,c.connectionDigest()));var page=service.list(p,source);
        return Map.of("schemaVersion","2.0","source",WorkflowJson.wire(source),"items",page.items().stream().map(i->Map.of("source",WorkflowJson.wire(i.source()),"item",i.item(),"asOf",i.asOf().toString(),"expiresAt",i.expiresAt().toString())).toList(),"truncated",page.truncated());
    }
}
