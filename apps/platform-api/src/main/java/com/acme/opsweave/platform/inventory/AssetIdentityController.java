package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.identity.api.*;
import com.acme.opsweave.inventory.application.*;
import com.acme.opsweave.inventory.domain.AssetIdentity;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.EntityId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public final class AssetIdentityController {
    private final PrincipalContext principals; private final AssetIdentityService service; private final String storage;
    public AssetIdentityController(PrincipalContext principals,AuthorizationService authorization,GetEntityUseCase entities,InventoryWiring wiring,
            @Value("${opsweave.inventory.identity-namespace:}") String namespace,@Value("${opsweave.inventory.cmdb-import-source:}") String source) {
        this.principals=principals; this.storage=wiring.label(); service=new AssetIdentityService(authorization,entities,wiring.assetIdentities(),namespace,source,Clock.systemUTC());
    }
    private static EntityId entity(String id) { return new EntityId(SourceReviewJson.uuid(id)); }
    private static void params(HttpServletRequest r,Set<String> fields) { if(!fields.containsAll(r.getParameterMap().keySet()) || r.getParameterMap().values().stream().anyMatch(v -> v.length!=1)) throw new IllegalArgumentException("Invalid identity parameters"); }
    @GetMapping("/api/v1/entities/{entityId}/identity-keys") public Map<String,Object> page(@PathVariable String entityId,@RequestParam(required=false) String after,@RequestParam(defaultValue="25") int limit,HttpServletRequest request) {
        params(request,Set.of("after","limit")); var p=principals.requirePrincipal(); var id=entity(entityId); var rows=service.page(p,id,after==null?null:SourceReviewJson.uuid(after),limit); var items=rows.stream().limit(limit).toList();
        var result=new LinkedHashMap<String,Object>(); result.put("schemaVersion","1.0"); result.put("storage",storage); result.put("tenantId",p.tenantId().value()); result.put("entityId",id.value().toString()); result.put("namespace",service.namespace());
        result.put("items",items.stream().map(AssetIdentityJson::wire).toList()); result.put("after",after); result.put("limit",limit); result.put("nextCursor",rows.size()>limit?items.getLast().id().toString():null); return result;
    }
    @PostMapping("/api/v1/entities/{entityId}/identity-keys") public Map<String,Object> claim(@PathVariable String entityId,HttpServletRequest request) throws IOException {
        params(request,Set.of()); var p=principals.requirePrincipal(); var id=entity(entityId); service.authorize(p,id);
        var n=PipelineJson.read(request,false); PipelineJson.fields(n,Set.of("requestId","expectedEntityVersion","expectedNamespace","value","reason")); var requestId=SourceReviewJson.uuid(PipelineJson.text(n,"requestId"));
        if(!service.namespace().equals(PipelineJson.text(n,"expectedNamespace"))) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Identity namespace changed");
        return AssetIdentityJson.receipt(service.change(p,id,requestId,AssetIdentity.Action.ASSERT,requestId,SourceReviewJson.positiveLong(n,"expectedEntityVersion"),PipelineJson.text(n,"value"),PipelineJson.text(n,"reason")));
    }
    @PostMapping("/api/v1/entities/{entityId}/identity-keys/{identityId}/revocations") public Map<String,Object> revoke(@PathVariable String entityId,@PathVariable String identityId,HttpServletRequest request) throws IOException {
        params(request,Set.of()); var p=principals.requirePrincipal(); var id=entity(entityId); service.authorize(p,id);
        var n=PipelineJson.read(request,false); PipelineJson.fields(n,Set.of("requestId","expectedEntityVersion","expectedNamespace","reason"));
        if(!service.namespace().equals(PipelineJson.text(n,"expectedNamespace"))) throw new com.acme.opsweave.inventory.domain.SourceReview.Conflict("Identity namespace changed");
        return AssetIdentityJson.receipt(service.change(p,id,SourceReviewJson.uuid(PipelineJson.text(n,"requestId")),AssetIdentity.Action.REVOKE,SourceReviewJson.uuid(identityId),SourceReviewJson.positiveLong(n,"expectedEntityVersion"),null,PipelineJson.text(n,"reason")));
    }
    @PostMapping("/api/v1/inventory/resolve-identity") public Map<String,Object> resolve(HttpServletRequest request) throws IOException {
        params(request,Set.of()); var n=PipelineJson.read(request,false); PipelineJson.fields(n,Set.of("value")); var result=service.resolve(principals.requirePrincipal(),PipelineJson.text(n,"value"));
        return Map.of("schemaVersion","1.0","storage",storage,"method","registered-asset-uuid","identity",AssetIdentityJson.wire(result.identity()),"entityVersion",result.entityVersion());
    }
}
