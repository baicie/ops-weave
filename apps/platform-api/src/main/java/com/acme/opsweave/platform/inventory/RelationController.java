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
    private static RelationModelReader.Definition model(ModelDefinition d){var e=d.endpoints();return new RelationModelReader.Definition(d.id(),d.revision(),e.from().id(),e.from().revision(),e.to().id(),e.to().revision(),e.cardinality().name());}
    private static final java.util.regex.Pattern UUID_PATTERN=java.util.regex.Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static UUID parseUuid(String v){try{return UUID.fromString(v);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid relation cursor");}}
    private static Instant parseInstant(String v){try{return Instant.parse(v);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid relation time");}}
    private static int positive(int v){if(v<1)throw new IllegalArgumentException("Invalid entity version");return v;}
}
