package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.application.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.integration.domain.CmdbImportPipeline;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.EntityId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
public final class SourceSnapshotController {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final PrincipalContext principals;private final AuthorizationService authorization;private final GetEntityUseCase entities;
    private final InventoryWiring wiring;private final String source,namespace;
    public SourceSnapshotController(PrincipalContext principals,AuthorizationService authorization,GetEntityUseCase entities,InventoryWiring wiring,
            @Value("${opsweave.inventory.cmdb-import-source:}")String source,@Value("${opsweave.inventory.identity-namespace:}")String namespace){this.principals=principals;this.authorization=authorization;this.entities=entities;this.wiring=wiring;this.source=source;this.namespace=namespace;}
    private Principal authorize(HttpServletRequest request,boolean write){
        return authorize(request,write,Set.of());
    }
    private Principal authorize(HttpServletRequest request,boolean write,Set<String> allowed){
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey()) || e.getValue().length!=1))throw new IllegalArgumentException("Unexpected query");
        var p=principals.requirePrincipal();
        if(write && (!p.resourceScope().isTenantWide() || !p.permissions().containsAll(Set.of(Permission.ENTITY_READ,Permission.ENTITY_MANAGE,Permission.SOURCE_SYNC))))throw new SourceReviewService.Access(403);
        if(source.isEmpty() || namespace.isEmpty() || !wiring.label().equals("postgres"))throw new SourceReviewService.Access(503);
        if(write && authorization.authorize(p,ResourceRef.source(p.tenantId(),source),Permission.SOURCE_SYNC).denied())throw new SourceReviewService.Access(403);
        return p;
    }
    @PostMapping("/api/v1/integrations/cmdb/binding-corrections") public Map<String,Object> correct(HttpServletRequest request)throws IOException{
        var p=authorize(request,true);var bytes=request.getInputStream().readNBytes(16385);if(bytes.length==0 || bytes.length>16384)throw new IllegalArgumentException("Invalid correction size");
        tools.jackson.databind.JsonNode n;try{n=JSON.readTree(bytes);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid correction JSON");}
        PipelineJson.fields(n,Set.of("command","mappingDigest"));var digest=PipelineJson.text(n,"mappingDigest");if(!CmdbImportPipeline.DIGEST.equals(digest))throw new SourceReview.Conflict("Mapping changed");
        return SourceBindingCorrectionJson.wire(wiring.sourceSnapshots().correct(p.tenantId(),p.subjectId().value(),source,namespace,SourceBindingCorrectionJson.command(n.get("command")),digest));
    }
    @GetMapping("/api/v1/integrations/cmdb/binding-corrections/{requestId}") public Map<String,Object> correction(@PathVariable String requestId,HttpServletRequest request){
        var p=authorize(request,true);return SourceBindingCorrectionJson.wire(wiring.sourceSnapshots().correction(p.tenantId(),p.subjectId().value(),source,namespace,SourceReviewJson.uuid(requestId)).orElseThrow(()->new SourceReviewService.Access(404)));
    }
    @GetMapping("/api/v1/entities/{entityId}/source-binding-corrections") public Map<String,Object> corrections(@PathVariable String entityId,HttpServletRequest request){
        var p=authorize(request,true,Set.of("after","limit"));var id=EntityId.parse(SourceReviewJson.uuid(entityId).toString());var access=entities.get(p,id);if(access.kind()!=EntityAccessKind.FOUND)throw new SourceReviewService.Access(access.kind()==EntityAccessKind.FORBIDDEN?403:404);
        var after=request.getParameter("after")==null?null:SourceReviewJson.uuid(request.getParameter("after"));int limit=request.getParameter("limit")==null?25:Integer.parseInt(request.getParameter("limit"));
        var rows=wiring.sourceSnapshots().corrections(p.tenantId(),source,namespace,id,after,limit);boolean more=rows.size()>limit;var items=rows.stream().limit(limit).toList();
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","1.0");result.put("storage","postgres");result.put("dataMode","import");result.put("tenantId",p.tenantId().value());result.put("sourceInstanceId",source);result.put("namespace",namespace);result.put("entityId",id.value().toString());result.put("after",after==null?null:after.toString());result.put("limit",limit);result.put("items",items.stream().map(SourceBindingCorrectionJson::wire).toList());result.put("nextCursor",more?items.getLast().command().requestId().toString():null);return result;
    }
    @GetMapping("/api/v1/integrations/cmdb/snapshots/config") public Map<String,Object> config(HttpServletRequest request){var p=authorize(request,true);var result=new LinkedHashMap<String,Object>(Map.of("schemaVersion","1.0","storage","postgres","dataMode","import","sourceInstanceId",source,"namespace",namespace,"mappingDigest",CmdbImportPipeline.DIGEST,"engine",SourceSnapshot.ENGINE,"maxRecords",SourceSnapshot.MAX_RECORDS,"maxAgeSeconds",SourceSnapshot.MAX_AGE.toSeconds()));result.put("tenantId",p.tenantId().value());result.put("actor",p.subjectId().value());return result;}
    @PostMapping("/api/v1/integrations/cmdb/snapshots") public Map<String,Object> ingest(HttpServletRequest request)throws IOException{
        var p=authorize(request,true);var bytes=request.getInputStream().readNBytes(131073);if(bytes.length==0 || bytes.length>131072)throw new IllegalArgumentException("Invalid snapshot size");
        tools.jackson.databind.JsonNode n;try{n=JSON.readTree(bytes);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid snapshot JSON");}
        PipelineJson.fields(n,Set.of("input","mappingDigest"));var digest=PipelineJson.text(n,"mappingDigest");if(!CmdbImportPipeline.DIGEST.equals(digest))throw new SourceReview.Conflict("Mapping changed");
        return SourceSnapshotJson.wire(wiring.sourceSnapshots().ingest(p.tenantId(),p.subjectId().value(),source,namespace,SourceSnapshotJson.input(n.get("input")),digest));
    }
    @GetMapping("/api/v1/integrations/cmdb/snapshots/{requestId}") public Map<String,Object> receipt(@PathVariable String requestId,HttpServletRequest request){var p=authorize(request,true);return SourceSnapshotJson.wire(wiring.sourceSnapshots().receipt(p.tenantId(),p.subjectId().value(),source,namespace,SourceReviewJson.uuid(requestId)).orElseThrow(()->new SourceReviewService.Access(404)));}
    @GetMapping("/api/v1/entities/{entityId}/source-presence") public Map<String,Object> presence(@PathVariable String entityId,HttpServletRequest request){
        var p=authorize(request,false);var id=EntityId.parse(SourceReviewJson.uuid(entityId).toString());var access=entities.get(p,id);if(access.kind()!=EntityAccessKind.FOUND)throw new SourceReviewService.Access(access.kind()==EntityAccessKind.FORBIDDEN?403:404);
        var page=wiring.sourceSnapshots().presence(p.tenantId(),id,source);return Map.of("schemaVersion","1.0","storage","postgres","dataMode","import","tenantId",p.tenantId().value(),"entityId",id.value().toString(),"evaluatedAt",page.evaluatedAt().toString(),"items",page.items().stream().map(v->{var m=new LinkedHashMap<>(SourceSnapshotJson.wire(v.presence()));m.put("status",v.presence().status(page.evaluatedAt(),v.identityActive()));return m;}).toList());
    }
}
