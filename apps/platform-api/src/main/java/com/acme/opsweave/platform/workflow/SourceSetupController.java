package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.catalog.application.ModelCatalogService;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.SourceSetupService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.*;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v1/integrations/sources")
public class SourceSetupController {
    private final PrincipalContext principals;
    private final InventoryWiring wiring;
    private final OpsweaveProperties properties;
    private final BuiltinCatalog builtin=new BuiltinCatalog();
    private final ModelCatalogService catalog;
    private final SourceSetupService service;
    public SourceSetupController(PrincipalContext principals,InventoryWiring wiring,OpsweaveProperties properties) {
        this.principals=principals;this.wiring=wiring;this.properties=properties;
        catalog=new ModelCatalogService(wiring.modelCatalog(),builtin.definitions(),Clock.systemUTC());
        service=new SourceSetupService(wiring.workflows(),this::model,this::connectionDigest,Clock.systemUTC());
    }
    private Principal principal(HttpServletRequest request) {if(!request.getParameterMap().isEmpty())throw new IllegalArgumentException();var p=principals.requirePrincipal();service.authorize(p);catalog.authorize(p,false);return p;}
    private ModelDefinition model(Principal p,WorkflowDefinition.Target target) {catalog.authorize(p,false);return builtin.definitions().stream().filter(m->m.ref().equals(target.ref())).findFirst().orElseGet(()->catalog.find(p,target.ref()).definition());}
    private SourceSetupService.Connection connectionDigest(Principal p,WorkflowDefinition.Source source) {
        if(source.kind().equals("MANUAL_SAMPLE"))return new SourceSetupService.Connection(WorkflowDefinition.hash(List.of("manual-samples-v1")),"MANUAL_SAMPLE");
        var connection=zabbix(p);if(connection==null||!source.instanceId().equals(connection.get("instanceId")))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);return new SourceSetupService.Connection((String)connection.get("digest"),(String)connection.get("dataMode"));
    }
    private Map<String,Object> zabbix(Principal p) {
        var z=properties.zabbix();if(z==null||z.sourceInstanceId()==null||!Set.of("jsonrpc","fixture").contains(Objects.toString(z.mode(),"")))return null;
        if(new Authorizer().decide(p,ResourceRef.source(p.tenantId(),z.sourceInstanceId()),Permission.SOURCE_SYNC).denied())return null;
        if(!z.sourceInstanceId().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))return null;
        String endpoint=null,reference=null;
        if(z.mode().equals("jsonrpc")) {
            try {var u=URI.create(z.url());if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||z.url().length()>1024)return null;endpoint=u.toString();}catch(RuntimeException invalid){return null;}
            if(z.secretRef()==null||!z.secretRef().matches("env:[A-Z][A-Z0-9_]{0,127}"))return null;
            reference=z.secretRef();
        }
        String digest=WorkflowDefinition.hash(List.of("managed-zabbix-config-v1",z.sourceInstanceId(),z.mode(),Objects.toString(endpoint,""),Objects.toString(reference,"")));
        var result=new LinkedHashMap<String,Object>();result.put("instanceId",z.sourceInstanceId());result.put("digest",digest);result.put("dataMode",z.mode().equals("fixture")?"fixture":"zabbix-jsonrpc");result.put("endpoint",endpoint);result.put("credentialRef",reference);return result;
    }
    private Map<String,Object> type(String id,String status,Map<String,Object> connection) {var value=new LinkedHashMap<String,Object>();value.put("id",id);value.put("status",status);value.put("connection",connection);return value;}
    private Map<String,Object> manual() {var c=new LinkedHashMap<String,Object>();c.put("instanceId","manual");c.put("digest",WorkflowDefinition.hash(List.of("manual-samples-v1")));c.put("dataMode","MANUAL_SAMPLE");c.put("endpoint",null);c.put("credentialRef",null);return c;}
    @GetMapping public Object list(HttpServletRequest request) {
        var p=principal(request);var remote=zabbix(p);var models=new ArrayList<ModelDefinition>(builtin.definitions().stream().filter(m->m.kind()==ModelDefinition.Kind.ENTITY&&!m.fields().isEmpty()).toList());var custom=catalog.published(p);models.addAll(custom.items().stream().map(com.acme.opsweave.catalog.api.ModelCatalogStore.Entry::definition).filter(m->m.kind()==ModelDefinition.Kind.ENTITY&&!m.fields().isEmpty()).toList());var setups=service.list(p);
        return Map.of("schemaVersion","1.0","storage",wiring.label(),"types",List.of(type("ZABBIX_HOST",remote==null?"UNAVAILABLE":"AVAILABLE",remote),type("MANUAL_SAMPLE","AVAILABLE",manual()),type("CMDB_SNAPSHOT","LEGACY_IMPORT",null)),"models",models.stream().map(m->Map.of("definition",CatalogJson.wire(m),"digest",m.digest())).toList(),"modelsTruncated",custom.truncated(),"setups",Map.of("items",setups.items().stream().map(SourceSetupJson::wire).toList(),"truncated",setups.truncated()));
    }
    @PostMapping(value="/confirm",consumes="application/json") public Object confirm(HttpServletRequest request)throws IOException {
        var p=principal(request);var n=CatalogJson.read(request);fields(n,Set.of("requestId","name","description","source","connectionDigest","target"));
        return wire(service.confirm(p,new SourceSetupService.Command(UUID.fromString(text(n,"requestId")),text(n,"name"),SourceSetupJson.description(n),SourceSetupJson.source(n.get("source")),text(n,"connectionDigest"),SourceSetupJson.target(n.get("target")))));
    }
    @GetMapping("/{id}")public Object read(@PathVariable UUID id,HttpServletRequest request) {return wire(service.read(principal(request),id));}
    private Object wire(SourceSetupService.Confirmed result) {return Map.of("setup",SourceSetupJson.wire(result.setup()),"workflow",WorkflowJson.wire(result.workflow()));}
}
