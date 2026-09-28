package com.acme.opsweave.platform.workflow;
import com.acme.opsweave.catalog.application.ModelCatalogService;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.*;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v1/integrations/workflows")
public class WorkflowController {
 private final PrincipalContext principals;private final InventoryWiring wiring;private final OpsweaveProperties properties;
 private final BuiltinCatalog builtin=new BuiltinCatalog();private final ModelCatalogService catalog;private final WorkflowService service;
 public WorkflowController(PrincipalContext principals,InventoryWiring wiring,OpsweaveProperties properties,HostPipelineService hosts){
  this.principals=principals;this.wiring=wiring;this.properties=properties;catalog=new ModelCatalogService(wiring.modelCatalog(),builtin.definitions(),Clock.systemUTC());
  service=new WorkflowService(wiring.workflows(),this::model,(p,source,batchId)->{
   if(!source.kind().equals("ZABBIX_HOST")||!source.instanceId().equals(properties.zabbix().sourceInstanceId()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
   // Reuse the existing source AND per-entity authorization and retained-v1 lineage boundary.
   var report=hosts.preview(p,batchId,PipelineDefinition.zabbixHostV1(),5);
   if(report.oversized()>0||report.rejected()>0||report.rows().isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
   var records=new ArrayList<Map<String,Object>>();for(var row:report.rows()){var h=row.candidate();if(h==null)throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);records.add(Map.of("name",h.name(),"ip",h.ip(),"lifecycle",h.lifecycle(),"entity_id",h.entityId()));}
   return new WorkflowService.Batch(records,report.dataMode(),report.retained(),report.missingRaw(),report.truncated(),report.sourceRunStatus());
  },Clock.systemUTC());
 }
 private ModelDefinition model(Principal p,WorkflowDefinition.Target target){catalog.authorize(p,false);return builtin.definitions().stream().filter(m->m.ref().equals(target.ref())).findFirst().orElseGet(()->catalog.find(p,target.ref()).definition());}
 private Principal principal(){var p=principals.requirePrincipal();service.authorize(p);catalog.authorize(p,false);return p;}
 @GetMapping public Object list(){var p=principal();var models=new ArrayList<ModelDefinition>(builtin.definitions().stream().filter(m->m.kind()==ModelDefinition.Kind.ENTITY).toList());var custom=catalog.published(p);models.addAll(custom.items().stream().map(com.acme.opsweave.catalog.api.ModelCatalogStore.Entry::definition).filter(m->m.kind()==ModelDefinition.Kind.ENTITY).toList());
  return Map.of("schemaVersion","2.0","storage",wiring.label(),"drafts",entries(service.drafts(p)),"published",entries(service.versions(p)),"models",models.stream().map(m->Map.of("definition",CatalogJson.wire(m),"digest",m.digest())).toList(),"modelsTruncated",custom.truncated(),"zabbixSource",Map.of("instanceId",Objects.toString(properties.zabbix().sourceInstanceId(),""),"mode",Objects.toString(properties.zabbix().mode(),"closed")),"runs",runs(service.runs(p)));
 }
 @GetMapping("/runs/{id}")public Object run(@PathVariable UUID id,HttpServletRequest request){if(request.getQueryString()!=null)throw new IllegalArgumentException();return WorkflowJson.detail(service.run(principal(),id));}
 private Object runs(WorkflowService.Page<WorkflowStore.Run> page){return Map.of("items",page.items().stream().map(WorkflowJson::summary).toList(),"truncated",page.truncated());}
 private Object entries(WorkflowService.Page<WorkflowStore.Entry> page){return Map.of("items",page.items().stream().map(WorkflowJson::wire).toList(),"truncated",page.truncated());}
 @PostMapping(value="/drafts",consumes="application/json")public Object save(HttpServletRequest request)throws IOException {var p=principal();var n=CatalogJson.read(request);fields(n,Set.of("definition","layout","expectedEditVersion"));var d=WorkflowJson.definition(n.get("definition"));checkSource(d);return WorkflowJson.wire(service.save(p,d,WorkflowJson.layout(n.get("layout")),integer(n,"expectedEditVersion",null)));}
 private void checkSource(WorkflowDefinition d){if(d.source().kind().equals("ZABBIX_HOST")&&!d.source().instanceId().equals(properties.zabbix().sourceInstanceId()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
 @GetMapping("/drafts/{id}/{revision}")public Object draft(@PathVariable String id,@PathVariable int revision){return WorkflowJson.wire(service.draft(principal(),id,revision));}
 @GetMapping("/versions/{id}/{revision}")public Object version(@PathVariable String id,@PathVariable int revision){return WorkflowJson.wire(service.version(principal(),id,revision));}
 @PostMapping(value="/publish",consumes="application/json")public Object publish(HttpServletRequest request)throws IOException {var p=principal();var n=CatalogJson.read(request);fields(n,Set.of("id","revision","editVersion","digest","previewId"));return WorkflowJson.wire(service.publish(p,text(n,"id"),integer(n,"revision",null),integer(n,"editVersion",null),text(n,"digest"),UUID.fromString(text(n,"previewId"))));}
 @PostMapping(value="/{mode:preview|run}",consumes="application/json")public Object evaluate(@PathVariable String mode,HttpServletRequest request)throws IOException {var p=principal();var n=CatalogJson.read(request);fields(n,Set.of("id","revision","editVersion","digest","samples","syncRunId","dryRun"));if(!n.has("dryRun")||!n.get("dryRun").isBoolean()||!n.get("dryRun").asBoolean())throw new IllegalArgumentException();
  var result=service.evaluate(p,text(n,"id"),integer(n,"revision",null),integer(n,"editVersion",null),text(n,"digest"),mode.equals("run"),n.has("samples")?WorkflowJson.samples(n.get("samples")):null,n.has("syncRunId")?UUID.fromString(text(n,"syncRunId")):null);
  if(CatalogJson.JSON.writeValueAsBytes(result).length>1048576)throw new IllegalArgumentException("Workflow report too large");return result;
 }
}
