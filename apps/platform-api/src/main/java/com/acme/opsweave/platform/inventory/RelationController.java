package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.catalog.api.ModelCatalogStore;
import com.acme.opsweave.identity.api.*;
import com.acme.opsweave.inventory.api.RelationModelReader;
import com.acme.opsweave.inventory.application.EntityRelationService;
import com.acme.opsweave.platform.catalog.BuiltinCatalog;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.EntityId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/entities/{entityId}/relations")
public final class RelationController {
    private final PrincipalContext principals; private final EntityRelationService service; private final String storage;
    public RelationController(PrincipalContext principals,AuthorizationService authorization,InventoryWiring wiring) {
        this.principals=principals;this.storage=wiring.label();var builtins=new BuiltinCatalog().definitions();
        RelationModelReader models=(tenant,id,revision)->builtins.stream().filter(d->d.ref().equals(new ModelDefinition.Ref(id,revision))&&d.kind()==ModelDefinition.Kind.RELATION).findFirst().map(RelationController::model).or(()->wiring.modelCatalog().find(tenant,new ModelDefinition.Ref(id,revision)).filter(e->e.definition().kind()==ModelDefinition.Kind.RELATION).map(ModelCatalogStore.Entry::definition).map(RelationController::model));
        this.service=new EntityRelationService(authorization,wiring.query(),wiring.relations(),models,Clock.systemUTC());
    }
    @GetMapping public ResponseEntity<?> page(@PathVariable String entityId,@RequestParam(required=false) String after,@RequestParam(required=false) String asOf,@RequestParam(defaultValue="25") int limit,HttpServletRequest request) {
        if(!UUID_PATTERN.matcher(entityId).matches()||!Set.of("after","asOf","limit").containsAll(request.getParameterMap().keySet())||request.getParameterMap().values().stream().anyMatch(v->v.length!=1)||limit<1||limit>50)throw new IllegalArgumentException("Invalid relation parameters");
        var p=principals.requirePrincipal();var id=EntityId.parse(entityId);UUID cursor=after==null?null:parseUuid(after);Instant cutoff=asOf==null?Instant.now():parseInstant(asOf);var rows=service.page(p,id,cursor,cutoff,limit);var items=rows.stream().limit(limit).map(RelationJson::wire).toList();
        var body=new LinkedHashMap<String,Object>();body.put("schemaVersion","1.0");body.put("storage",storage);body.put("tenantId",p.tenantId().value());body.put("entityId",id.value().toString());body.put("asOf",cutoff.toString());body.put("items",items);body.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);return ResponseEntity.ok(body);
    }
    @PostMapping(consumes="application/json") public ResponseEntity<?> create(@PathVariable String entityId,HttpServletRequest request)throws IOException {
        if(!UUID_PATTERN.matcher(entityId).matches()||!request.getParameterMap().isEmpty())throw new IllegalArgumentException("Invalid relation request");var p=principals.requirePrincipal();var n=PipelineJson.read(request,false);
        PipelineJson.fields(n,Set.of("requestId","relationType","relationRevision","fromEntityId","toEntityId","validFrom","validTo","sourceRef","dataMode","expectedFromVersion","expectedToVersion"));
        var from=EntityId.parse(PipelineJson.text(n,"fromEntityId"));var to=EntityId.parse(PipelineJson.text(n,"toEntityId"));if(!from.value().toString().equalsIgnoreCase(entityId))throw new IllegalArgumentException("Path endpoint mismatch");
        var validTo=n.get("validTo");var result=service.write(p,UUID.fromString(PipelineJson.text(n,"requestId")),PipelineJson.text(n,"relationType"),PipelineJson.integer(n,"relationRevision",null),from,to,parseInstant(PipelineJson.text(n,"validFrom")),validTo==null||validTo.isNull()?null:parseInstant(PipelineJson.text(n,"validTo")),n.has("sourceRef")?PipelineJson.text(n,"sourceRef"):"operator",n.has("dataMode")?PipelineJson.text(n,"dataMode"):"unknown",positive(PipelineJson.integer(n,"expectedFromVersion",null)),positive(PipelineJson.integer(n,"expectedToVersion",null)));
        return ResponseEntity.ok(RelationJson.receipt(result,UUID.fromString(PipelineJson.text(n,"requestId"))));
    }
    @GetMapping("/history") public ResponseEntity<?> history(@PathVariable String entityId,@RequestParam(required=false) String after,@RequestParam(defaultValue="25") int limit,HttpServletRequest request) {
        if(!UUID_PATTERN.matcher(entityId).matches()||!Set.of("after","limit").containsAll(request.getParameterMap().keySet())||request.getParameterMap().values().stream().anyMatch(v->v.length!=1)||limit<1||limit>50)throw new IllegalArgumentException("Invalid relation parameters");
        var p=principals.requirePrincipal();var id=EntityId.parse(entityId);UUID cursor=after==null?null:parseUuid(after);var rows=service.history(p,id,cursor,limit);var items=rows.stream().limit(limit).map(RelationJson::historyWire).toList();
        Object next=null;
        if(rows.size()>limit&&items.getLast().get("relation") instanceof Map<?,?> last)next=last.get("id");
        var body=new LinkedHashMap<String,Object>();body.put("schemaVersion","1.0");body.put("storage",storage);body.put("tenantId",p.tenantId().value());body.put("entityId",id.value().toString());body.put("items",items);body.put("nextCursor",next);return ResponseEntity.ok(body);
    }
    @PostMapping(value="/{relationId}/close",consumes="application/json") public ResponseEntity<?> close(@PathVariable String entityId,@PathVariable String relationId,HttpServletRequest request)throws IOException {
        if(!UUID_PATTERN.matcher(entityId).matches()||!UUID_PATTERN.matcher(relationId).matches()||!request.getParameterMap().isEmpty())throw new IllegalArgumentException("Invalid relation request");var p=principals.requirePrincipal();var n=PipelineJson.read(request,false);
        PipelineJson.fields(n,Set.of("requestId","relationId","reason","validTo","expectedVersion"));
        var target=UUID.fromString(PipelineJson.text(n,"relationId"));if(!target.toString().equalsIgnoreCase(relationId))throw new IllegalArgumentException("Path relation mismatch");
        var validTo=n.get("validTo");var result=service.close(p,UUID.fromString(PipelineJson.text(n,"requestId")),target,validTo==null||validTo.isNull()?null:parseInstant(PipelineJson.text(n,"validTo")),PipelineJson.text(n,"reason"),positive(PipelineJson.integer(n,"expectedVersion",null)));
        return ResponseEntity.ok(RelationJson.receipt(result,UUID.fromString(PipelineJson.text(n,"requestId"))));
    }
    @PostMapping(value="/{relationId}/correct",consumes="application/json") public ResponseEntity<?> correct(@PathVariable String entityId,@PathVariable String relationId,HttpServletRequest request)throws IOException {
        if(!UUID_PATTERN.matcher(entityId).matches()||!UUID_PATTERN.matcher(relationId).matches()||!request.getParameterMap().isEmpty())throw new IllegalArgumentException("Invalid relation request");var p=principals.requirePrincipal();var n=PipelineJson.read(request,false);
        PipelineJson.fields(n,Set.of("requestId","relationId","reason","expectedVersion","fromEntityId","toEntityId","relationType","relationRevision"));
        var target=UUID.fromString(PipelineJson.text(n,"relationId"));if(!target.toString().equalsIgnoreCase(relationId))throw new IllegalArgumentException("Path relation mismatch");
        var requestId=UUID.fromString(PipelineJson.text(n,"requestId"));
        var result=service.correct(p,requestId,target,PipelineJson.text(n,"reason"),positive(PipelineJson.integer(n,"expectedVersion",null)),EntityId.parse(PipelineJson.text(n,"fromEntityId")),EntityId.parse(PipelineJson.text(n,"toEntityId")),PipelineJson.text(n,"relationType"),PipelineJson.integer(n,"relationRevision",null));
        return ResponseEntity.ok(RelationJson.correctionReceipt(result,target,requestId));
    }
    @GetMapping("/corrections") public ResponseEntity<?> corrections(@PathVariable String entityId,@RequestParam(required=false) String after,@RequestParam(defaultValue="25") int limit,HttpServletRequest request) {
        if(!UUID_PATTERN.matcher(entityId).matches()||!Set.of("after","limit").containsAll(request.getParameterMap().keySet())||request.getParameterMap().values().stream().anyMatch(v->v.length!=1)||limit<1||limit>50)throw new IllegalArgumentException("Invalid relation parameters");
        var p=principals.requirePrincipal();var id=EntityId.parse(entityId);UUID cursor=after==null?null:parseUuid(after);
        var rows=service.corrections(p,id,cursor,limit);var items=rows.stream().limit(limit).map(r->RelationJson.correctionRecord(p.tenantId().value(),r)).toList();
        var body=new LinkedHashMap<String,Object>();body.put("schemaVersion","1.0");body.put("storage",storage);body.put("tenantId",p.tenantId().value());body.put("entityId",id.value().toString());body.put("items",items);
        body.put("nextCursor",rows.size()>limit?items.getLast().get("requestId"):null);return ResponseEntity.ok(body);
    }
    @GetMapping("/{relationId}/confirmations") public ResponseEntity<?> confirmations(@PathVariable String entityId,@PathVariable String relationId,HttpServletRequest request) {
        if(!UUID_PATTERN.matcher(entityId).matches()||!UUID_PATTERN.matcher(relationId).matches()||!request.getParameterMap().isEmpty())throw new IllegalArgumentException("Invalid relation parameters");
        var p=principals.requirePrincipal();var target=UUID.fromString(relationId);var view=service.confirmations(p,target);
        return ResponseEntity.ok(RelationJson.confirmationView(storage,p.tenantId().value(),view.relation(),view.entries()));
    }
    @PostMapping(value="/{relationId}/confirmations",consumes="application/json") public ResponseEntity<?> confirm(@PathVariable String entityId,@PathVariable String relationId,HttpServletRequest request)throws IOException {
        if(!UUID_PATTERN.matcher(entityId).matches()||!UUID_PATTERN.matcher(relationId).matches()||!request.getParameterMap().isEmpty())throw new IllegalArgumentException("Invalid relation request");var p=principals.requirePrincipal();var n=PipelineJson.read(request,false);
        PipelineJson.fields(n,Set.of("requestId","sourceKind","sourceKey","batchRef","dataMode"));
        var target=UUID.fromString(relationId);var requestId=UUID.fromString(PipelineJson.text(n,"requestId"));
        var result=service.confirm(p,requestId,target,PipelineJson.text(n,"sourceKind"),PipelineJson.text(n,"sourceKey"),PipelineJson.text(n,"batchRef"),PipelineJson.text(n,"dataMode"));
        return ResponseEntity.ok(RelationJson.confirmationReceipt(result,target,requestId));
    }
    @PostMapping(value="/{relationId}/confirmations/withdraw",consumes="application/json") public ResponseEntity<?> withdraw(@PathVariable String entityId,@PathVariable String relationId,HttpServletRequest request)throws IOException {
        if(!UUID_PATTERN.matcher(entityId).matches()||!UUID_PATTERN.matcher(relationId).matches()||!request.getParameterMap().isEmpty())throw new IllegalArgumentException("Invalid relation request");var p=principals.requirePrincipal();var n=PipelineJson.read(request,false);
        PipelineJson.fields(n,Set.of("requestId","sourceKind","sourceKey","batchRef","reason"));
        var target=UUID.fromString(relationId);var requestId=UUID.fromString(PipelineJson.text(n,"requestId"));
        var result=service.withdraw(p,requestId,target,PipelineJson.text(n,"sourceKind"),PipelineJson.text(n,"sourceKey"),PipelineJson.text(n,"batchRef"),PipelineJson.text(n,"reason"));
        return ResponseEntity.ok(RelationJson.confirmationReceipt(result,target,requestId));
    }
    private static RelationModelReader.Definition model(ModelDefinition d){var e=d.endpoints();return new RelationModelReader.Definition(d.id(),d.revision(),e.from().id(),e.from().revision(),e.to().id(),e.to().revision(),e.cardinality().name());}
    private static final java.util.regex.Pattern UUID_PATTERN=java.util.regex.Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static UUID parseUuid(String v){try{return UUID.fromString(v);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid relation cursor");}}
    private static Instant parseInstant(String v){try{return Instant.parse(v);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid relation time");}}
    private static int positive(int v){if(v<1)throw new IllegalArgumentException("Invalid entity version");return v;}
}
