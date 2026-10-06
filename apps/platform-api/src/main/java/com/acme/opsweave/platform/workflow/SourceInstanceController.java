package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v2/data-sources")
public final class SourceInstanceController {
    private final PrincipalContext principals;
    private final InventoryWiring wiring;
    private final SourceInstanceService service;
    private final SourceSetupService setups;
    public SourceInstanceController(PrincipalContext principals,InventoryWiring wiring,OpsweaveProperties properties,SourceConnectionWiring configured){
        this.principals=principals;this.wiring=wiring;var connections=configured.connections();
        service=new SourceInstanceService(wiring.workflows(),connections,Clock.systemUTC());
        setups=new SourceSetupService(wiring.workflows(),(p,t)->{throw new IllegalArgumentException();},connections,Clock.systemUTC());
    }
    private Principal principal(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw new IllegalArgumentException();var p=principals.requirePrincipal();service.authorize(p);return p;}
    @GetMapping public Object list(HttpServletRequest request){var page=service.list(principal(request));return Map.of("schemaVersion","2.0","storage",wiring.label(),"items",page.items().stream().map(SourceInstanceJson::wire).toList(),"truncated",page.truncated());}
    @GetMapping("/{id}")public Object read(@PathVariable UUID id,HttpServletRequest request){return Map.of("schemaVersion","2.0","instance",SourceInstanceJson.wire(service.read(principal(request),id)));}
    @PostMapping(consumes="application/json")public Object create(HttpServletRequest request)throws IOException{
        var p=principal(request);var n=CatalogJson.read(request);fields(n,Set.of("requestId","name","description","source","connectionDigest"));
        if(!text(n,"name").equals(text(n,"name").trim()))throw new IllegalArgumentException();
        var saved=setups.confirm(p,new SourceSetupService.Command(UUID.fromString(text(n,"requestId")),text(n,"name"),SourceSetupJson.description(n),SourceSetupJson.source(n.get("source")),text(n,"connectionDigest"),null));
        return Map.of("schemaVersion","2.0","instance",SourceInstanceJson.wire(SourceInstance.initial(saved.setup())));
    }
    @PatchMapping(value="/{id}",consumes="application/json")public Object edit(@PathVariable UUID id,HttpServletRequest request)throws IOException{
        var p=principal(request);var n=CatalogJson.read(request);fields(n,Set.of("requestId","expectedEditVersion","name","description","connectionDigest","state"));
        var receipt=service.edit(p,id,new SourceInstanceService.Edit(UUID.fromString(text(n,"requestId")),integer(n,"expectedEditVersion",null),text(n,"name"),SourceSetupJson.description(n),text(n,"connectionDigest"),text(n,"state")));
        return Map.of("schemaVersion","2.0","receipt",SourceInstanceJson.wire(receipt));
    }
    @GetMapping("/{id}/configurations")public Object configurations(@PathVariable UUID id,HttpServletRequest request){return Map.of("schemaVersion","2.0","sourceId",id,"items",service.configurations(principal(request),id));}
    @GetMapping("/{id}/commands/{requestId}")public Object receipt(@PathVariable UUID id,@PathVariable UUID requestId,HttpServletRequest request){return Map.of("schemaVersion","2.0","receipt",SourceInstanceJson.wire(service.receipt(principal(request),id,requestId)));}
}
