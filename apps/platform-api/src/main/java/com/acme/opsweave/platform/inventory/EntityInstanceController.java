package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.catalog.api.ModelCatalogStore;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.identity.api.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.EntityInstanceStore;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.catalog.BuiltinCatalog;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.type.TypeReference;

/** Controlled model-backed entity instance creation/update. Source scans use their own fenced port. */
@RestController
@RequestMapping("/api/v1/entities")
public final class EntityInstanceController {
    private final PrincipalContext principals; private final AuthorizationService authorization; private final InventoryWiring wiring; private final String storage;
    private final List<ModelDefinition> builtins=new BuiltinCatalog().definitions(); private final Clock clock=Clock.systemUTC();
    public EntityInstanceController(PrincipalContext principals,AuthorizationService authorization,InventoryWiring wiring){this.principals=principals;this.authorization=authorization;this.wiring=wiring;this.storage=wiring.label();}
    @PostMapping(consumes="application/json") public ResponseEntity<?> write(HttpServletRequest request)throws IOException{
        if(!request.getParameterMap().isEmpty())throw new IllegalArgumentException("Invalid entity request");var p=principals.requirePrincipal();var n=PipelineJson.read(request,false);
        PipelineJson.fields(n,Set.of("requestId","entityId","model","name","lifecycle","attributes","expectedVersion"));var requestId=parseUuid(PipelineJson.text(n,"requestId"));var entityId=new EntityId(parseUuid(PipelineJson.text(n,"entityId")));
        authorize(p,entityId);var ref=modelRef(n.get("model"));var model=resolve(p,ref);if(model.kind()!=ModelDefinition.Kind.ENTITY)throw new EntityInstanceStore.Conflict("Entity model required");
        var attrs=attributes(n.get("attributes"));var preview=ModelPreview.evaluate(model,attrs);if(!preview.valid())throw new IllegalArgumentException("Entity attributes do not satisfy model");
        try { com.acme.opsweave.inventory.domain.EntityReadLimits.checkForWrite(preview.values()); }
        catch (IllegalStateException oversized) { throw new IllegalArgumentException("Entity attributes exceed write budget"); }
        var name=PipelineJson.text(n,"name");var lifecycle=n.has("lifecycle")?Lifecycle.valueOf(PipelineJson.text(n,"lifecycle")):Lifecycle.ACTIVE;Long expected=n.has("expectedVersion")&&!n.get("expectedVersion").isNull()?longValue(n.get("expectedVersion")):null;
        var entity=new Entity(entityId,p.tenantId(),entityType(model),name,lifecycle,expected==null?1:expected+1,clock.instant(),preview.values());var result=wiring.entityInstances().write(p.tenantId(),requestId,entity,expected,model.id(),model.revision(),model.digest());
        return ResponseEntity.ok(receipt(result,p,model));
    }
    private void authorize(Principal p,EntityId id){if(!p.has(com.acme.opsweave.identity.domain.Permission.ENTITY_MANAGE)||authorization.authorize(p,ResourceRef.entity(p.tenantId(),id),com.acme.opsweave.identity.domain.Permission.ENTITY_MANAGE).denied())throw new EntityInstanceStore.Access("Entity management denied");}
    private ModelDefinition resolve(Principal p,ModelDefinition.Ref ref){return builtins.stream().filter(d->d.ref().equals(ref)).findFirst().orElseGet(()->wiring.modelCatalog().find(p.tenantId(),ref).map(ModelCatalogStore.Entry::definition).orElseThrow(()->new EntityInstanceStore.Conflict("Entity model is not published")));}
    private static ModelDefinition.Ref modelRef(tools.jackson.databind.JsonNode n){PipelineJson.fields(n,Set.of("id","revision"));return new ModelDefinition.Ref(PipelineJson.text(n,"id"),PipelineJson.integer(n,"revision",null));}
    private static Map<String,Object> attributes(tools.jackson.databind.JsonNode n){if(n==null||!n.isObject())throw new IllegalArgumentException("Invalid entity attributes");return SourceReviewJson.JSON.convertValue(n,new TypeReference<LinkedHashMap<String,Object>>(){});}
    private static long longValue(tools.jackson.databind.JsonNode n){if(!n.isIntegralNumber()||!n.canConvertToLong()||n.asLong()<1||n.asLong()>9_007_199_254_740_990L)throw new IllegalArgumentException("Invalid entity version");return n.asLong();}
    private static final java.util.regex.Pattern UUID_PATTERN=java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static UUID parseUuid(String value){if(!UUID_PATTERN.matcher(value).matches())throw new IllegalArgumentException("Invalid UUID");return UUID.fromString(value);}
    private static String entityType(ModelDefinition m){if(m.id().equals("builtin.host"))return "Host";if(m.id().equals("builtin.application"))return "Application";if(m.id().equals("builtin.service"))return "Service";if(m.id().equals("builtin.database"))return "Database";if(m.id().equals("builtin.network_interface"))return "NetworkInterface";return m.id();}
    private Map<String,Object> receipt(EntityInstanceStore.WriteResult result,Principal p,ModelDefinition model){var m=new LinkedHashMap<String,Object>();m.put("schemaVersion","1.0");m.put("storage",storage);m.put("replayed",result.replayed());m.put("tenantId",p.tenantId().value());m.put("model",Map.of("id",model.id(),"revision",model.revision(),"digest",model.digest()));m.put("entity",EntityController.body(new com.acme.opsweave.inventory.api.InventoryQuery.EntityView(result.entity().id(),result.entity().tenantId(),result.entity().entityType(),result.entity().name(),result.entity().lifecycle().name(),result.entity().version(),result.entity().attributes(),result.entity().model())));return m;}
}
