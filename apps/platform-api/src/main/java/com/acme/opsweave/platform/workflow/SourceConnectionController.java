package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.SourceConnectionService;
import com.acme.opsweave.integration.domain.SourceConnectionConfiguration;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v2/data-sources")
public final class SourceConnectionController {
    private final PrincipalContext principals;private final SourceConnectionService service;private final InventoryWiring wiring;
    public SourceConnectionController(PrincipalContext principals,SourceConnectionWiring connections,InventoryWiring wiring){this.principals=principals;service=connections.service();this.wiring=wiring;}
    private void query(HttpServletRequest r){if(!r.getParameterMap().isEmpty()||!r.getRequestURI().matches("/api/v2/data-sources/(?:connections|[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}/connection(?:/history|/commands/[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12})?)"))throw new IllegalArgumentException();}
    private List<String> hostGroupIds(JsonNode value){if(value==null||!value.isArray())throw new IllegalArgumentException();var ids=new ArrayList<String>();for(var item:value){if(!item.isTextual())throw new IllegalArgumentException();ids.add(item.textValue());}return SourceConnectionConfiguration.normalizeHostGroupIds(ids);}
    private Object receipt(SourceConnectionService.Receipt r){return Map.of("schemaVersion","2.0","storage",wiring.label(),"receipt",SourceInstanceJson.wire(r.receipt()),"connection",SourceConnectionJson.wire(r.connection()));}
    @PostMapping(value="/connections",consumes="application/json")public Object create(HttpServletRequest r)throws java.io.IOException{return write(null,r);}
    @PatchMapping(value="/{id}/connection",consumes="application/json")public Object edit(@PathVariable UUID id,HttpServletRequest r)throws java.io.IOException{return write(id,r);}
    private Object write(UUID id,HttpServletRequest r)throws java.io.IOException{query(r);var p=principals.requirePrincipal();var n=CatalogJson.read(r);boolean create=id==null;fields(n,create?Set.of("requestId","name","description","endpointPin","credentialPin","hostGroupIds"):Set.of("requestId","expectedEditVersion","name","description","endpointPin","credentialPin","hostGroupIds"));var request=SourceConnectionJson.uuid(n,"requestId");if(create)id=request;var c=new SourceConnectionService.Write(request,create?0:integer(n,"expectedEditVersion",null),text(n,"name"),SourceSetupJson.description(n),SourceConnectionJson.endpointPin(n.get("endpointPin")),SourceConnectionJson.credentialPin(n.get("credentialPin")),hostGroupIds(n.get("hostGroupIds")));return receipt(service.write(p,id,c,create));}
    @GetMapping("/{id}/connection")public Object read(@PathVariable UUID id,HttpServletRequest r){query(r);var v=service.read(principals.requirePrincipal(),id);var n=new LinkedHashMap<String,Object>();n.put("schemaVersion","2.0");n.put("storage",wiring.label());n.put("instance",SourceInstanceJson.wire(v.instance()));n.put("connection",v.connection()==null?null:SourceConnectionJson.wire(v.connection()));n.put("availability",v.availability());n.put("canConfigure",v.canConfigure());return n;}
    @GetMapping("/{id}/connection/history")public Object history(@PathVariable UUID id,HttpServletRequest r){query(r);return Map.of("schemaVersion","2.0","storage",wiring.label(),"sourceId",id,"items",service.history(principals.requirePrincipal(),id).stream().map(SourceConnectionJson::wire).toList());}
    @GetMapping("/{id}/connection/commands/{requestId}")public Object original(@PathVariable UUID id,@PathVariable UUID requestId,HttpServletRequest r){query(r);return receipt(service.receipt(principals.requirePrincipal(),id,requestId));}
}
