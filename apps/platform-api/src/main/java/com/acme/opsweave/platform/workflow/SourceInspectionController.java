package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.SourceInspectionService;
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
@RequestMapping("/api/v2/data-sources/{id}")
public final class SourceInspectionController {
    private final PrincipalContext principals;private final InventoryWiring wiring;private final SourceInspectionService service;
    public SourceInspectionController(PrincipalContext principals,InventoryWiring wiring,OpsweaveProperties properties,Connector connector,SourceConnectionWiring configured,RegisteredHostSourceReader registered){
        this.principals=principals;this.wiring=wiring;var legacy=new HostSourceInspectionReader(connector,properties);
        var reader=new SourceInspectionService.Reader(){
            public SourceInspectionService.Result read(com.acme.opsweave.identity.domain.Principal p,com.acme.opsweave.integration.domain.WorkflowDefinition.Source source,String kind){return legacy.read(p,source,kind);}
            public SourceInspectionService.Result read(com.acme.opsweave.identity.domain.Principal p,com.acme.opsweave.integration.domain.SourceInstance instance,String kind){
                var snapshot=configured.service().configuration(p,instance.id(),instance.configurationRevision());
                if(snapshot.isEmpty())return legacy.read(p,instance.source(),kind);var c=snapshot.get();
                if(!c.connectionDigest().equals(instance.connectionDigest()))throw new com.acme.opsweave.integration.domain.WorkflowFailure(com.acme.opsweave.integration.domain.WorkflowFailure.Code.SOURCE_UNAVAILABLE);
                return registered.read(p,instance.source().instanceId(),c.endpoint().pin(),c.credentialPin(),c.hostGroupIds(),kind);
            }
            public com.acme.opsweave.integration.domain.SourceMetricPage readMetricPage(com.acme.opsweave.identity.domain.Principal p,com.acme.opsweave.integration.domain.SourceInstance instance,com.acme.opsweave.integration.domain.SourceInspection pending,com.acme.opsweave.integration.domain.SourceMetricPage previous){
                var snapshot=configured.service().configuration(p,instance.id(),instance.configurationRevision());
                if(snapshot.isEmpty()){
                    if(!instance.dataMode().equals("fixture"))throw new com.acme.opsweave.integration.domain.WorkflowFailure(com.acme.opsweave.integration.domain.WorkflowFailure.Code.SOURCE_UNAVAILABLE);
                    return new com.acme.opsweave.integration.infrastructure.ZabbixMetricPageReader(com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog.load(getClass().getClassLoader()))
                        .fixture(new Connector.SourceContext(p.tenantId(),instance.source().instanceId(),properties.zabbix().secretRef()),pending.requestId(),pending.asOf(),previous);
                }
                var c=snapshot.get();if(!c.connectionDigest().equals(instance.connectionDigest()))throw new com.acme.opsweave.integration.domain.WorkflowFailure(com.acme.opsweave.integration.domain.WorkflowFailure.Code.SOURCE_UNAVAILABLE);
                return registered.readMetricPage(p,instance,c.endpoint().pin(),c.credentialPin(),c.hostGroupIds(),pending,previous);
            }
        };
        service=new SourceInspectionService(wiring.workflows(),configured.connections(),reader,Clock.systemUTC());
    }
    private void query(HttpServletRequest request){if(!request.getParameterMap().isEmpty()||!request.getRequestURI().matches("/api/v2/data-sources/[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}/(?:test|connection-check|discover|discover-metrics|metric-discoveries|connection-checks|inspections(?:/[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12})?)"))throw new IllegalArgumentException();}
    private Map<String,Object> item(SourceInspectionService.View v){return Map.of("inspection",SourceInspectionJson.wire(v.inspection()),"validity",v.validity());}
    @PostMapping(value="/test",consumes="application/json")public Object test(@PathVariable UUID id,HttpServletRequest request)throws IOException{return run(id,"TEST",request);}
    /** Instance-scoped connection check. It pins the current configuration/credential and performs
     * the same bounded authenticated read as TEST, while keeping the API vocabulary explicit. */
    @PostMapping(value="/connection-check",consumes="application/json")public Object connectionCheck(@PathVariable UUID id,HttpServletRequest request)throws IOException{return run(id,"TEST",request);}
    @PostMapping(value="/discover",consumes="application/json")public Object discover(@PathVariable UUID id,HttpServletRequest request)throws IOException{return run(id,"DISCOVER",request);}
    @PostMapping(value="/discover-metrics",consumes="application/json")public Object discoverMetrics(@PathVariable UUID id,HttpServletRequest request)throws IOException{return run(id,"DISCOVER_METRICS",request);}
    @PostMapping(value="/metric-discoveries",consumes="application/json")public Object metricPage(@PathVariable UUID id,HttpServletRequest request)throws IOException{
        query(request);var p=principals.requirePrincipal();var n=CatalogJson.read(request);fields(n,Set.of("requestId","configurationRevision","connectionDigest","previousRequestId"));
        if(!n.has("previousRequestId"))throw new IllegalArgumentException();
        String key=text(n,"requestId");var requestId=UUID.fromString(key);if(!requestId.toString().equals(key))throw new IllegalArgumentException();
        UUID previous=null;if(!n.get("previousRequestId").isNull()){String parent=text(n,"previousRequestId");previous=UUID.fromString(parent);if(!previous.toString().equals(parent))throw new IllegalArgumentException();}
        var c=new SourceInspectionService.Command(requestId,integer(n,"configurationRevision",null),text(n,"connectionDigest"),previous);
        return Map.of("schemaVersion","2.0","storage",wiring.label(),"view",item(service.run(p,id,"DISCOVER_METRIC_PAGE",c)));
    }
    private Object run(UUID id,String kind,HttpServletRequest request)throws IOException{query(request);var p=principals.requirePrincipal();var n=CatalogJson.read(request);fields(n,Set.of("requestId","configurationRevision","connectionDigest"));String key=text(n,"requestId");var requestId=UUID.fromString(key);if(!requestId.toString().equals(key))throw new IllegalArgumentException();var c=new SourceInspectionService.Command(requestId,integer(n,"configurationRevision",null),text(n,"connectionDigest"));return Map.of("schemaVersion","2.0","storage",wiring.label(),"view",item(service.run(p,id,kind,c)));}
    @GetMapping("/inspections/{requestId}")public Object read(@PathVariable UUID id,@PathVariable UUID requestId,HttpServletRequest request){query(request);return Map.of("schemaVersion","2.0","storage",wiring.label(),"view",item(service.read(principals.requirePrincipal(),id,requestId)));}
    @GetMapping("/inspections")public Object recent(@PathVariable UUID id,HttpServletRequest request){query(request);return Map.of("schemaVersion","2.0","storage",wiring.label(),"sourceId",id,"items",service.recent(principals.requirePrincipal(),id).stream().map(this::item).toList());}
    @GetMapping("/connection-checks")public Object connectionChecks(@PathVariable UUID id,HttpServletRequest request){query(request);var items=service.recent(principals.requirePrincipal(),id,"TEST").stream().map(this::item).toList();return Map.of("schemaVersion","2.0","storage",wiring.label(),"sourceId",id,"items",items);}
}
