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
 private final BuiltinCatalog builtin=new BuiltinCatalog();private final ModelCatalogService catalog;private final WorkflowService service;private final WorkflowRuntimeService runtime;
 private final MappingRegistry metricMappings=com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog.load(getClass().getClassLoader());
 public WorkflowController(PrincipalContext principals,InventoryWiring wiring,OpsweaveProperties properties,HostPipelineService hosts,SourceConnectionWiring connections,RegisteredHostSourceReader registered){
  this.principals=principals;this.wiring=wiring;this.properties=properties;catalog=new ModelCatalogService(wiring.modelCatalog(),builtin.definitions(),Clock.systemUTC());
  WorkflowService.Samples samples=(p,source,batchId)->{
   if(source.configuration()!=null)throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
   if(!source.kind().equals("ZABBIX_HOST")||!source.instanceId().equals(properties.zabbix().sourceInstanceId()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
   // Reuse the existing source AND per-entity authorization and retained-v1 lineage boundary.
   var report=hosts.preview(p,batchId,PipelineDefinition.zabbixHostV1(),5);
   if(report.oversized()>0||report.rejected()>0||report.rows().isEmpty())throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
   var records=new ArrayList<Map<String,Object>>();for(var row:report.rows()){var h=row.candidate();if(h==null)throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);records.add(Map.of("name",h.name(),"ip",h.ip(),"lifecycle",h.lifecycle(),"entity_id",h.entityId()));}
   // The collector's explicit Fixture label maps to the closed workflow origin; unknown modes still fail.
   String origin=report.dataMode().equals("labeled-fixture")?"fixture":report.dataMode();
   return new WorkflowService.Batch(records,origin,report.retained(),report.missingRaw(),report.truncated(),report.sourceRunStatus());
  };
  var metricSources=new WorkflowMetricSourceService(wiring.workflows(),connections.service(),Clock.systemUTC());
  var logSources=new WorkflowLogSourceService(wiring.workflows(),connections.service(),Clock.systemUTC());
  WorkflowService.Sources sources=new WorkflowService.Sources(){public void require(Principal p,WorkflowDefinition.Source source,WorkflowStore.Session s,boolean available){if(source.kind().equals("MANUAL_SAMPLE"))return;if(source.configuration()!=null){connections.service().workflowConfiguration(s,p,source,available);return;}if(new com.acme.opsweave.identity.domain.Authorizer().decide(p,com.acme.opsweave.identity.domain.ResourceRef.source(p.tenantId(),source.instanceId()),com.acme.opsweave.identity.domain.Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);if(available&&!source.instanceId().equals(properties.zabbix().sourceInstanceId()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);} public void requireTarget(Principal p,WorkflowDefinition.Source source,WorkflowDefinition.Target target,WorkflowStore.Session s,boolean available){metricSources.requireTarget(s,p,source,target,available);logSources.requireTarget(s,p,source,target,available);}};
  WorkflowService.Samples previewSamples=(p,source,batchId)->{if(source.configuration()==null)return samples.read(p,source,batchId);var c=connections.service().workflowConfiguration(p,source);return source.kind().equals("ZABBIX_METRIC")?registered.metricPreview(p,source,c.endpoint().pin(),c.credentialPin(),c.hostGroupIds()):source.kind().equals("ZABBIX_LOG")?registered.logPreview(p,source,c.endpoint().pin(),c.credentialPin(),c.hostGroupIds()):registered.preview(p,source.instanceId(),c.endpoint().pin(),c.credentialPin(),c.hostGroupIds());};
  service=new WorkflowService(wiring.workflows(),this::model,previewSamples,sources,(p,pin)->metricMappings.find(pin).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.MAPPING_CHANGED)),Clock.systemUTC());
  runtime=new WorkflowRuntimeService(wiring.workflows(),this::model,samples,(p,source,after,afterId)->{
   var recent=wiring.syncRuns().completedAfter(p.tenantId(),source.instanceId(),"host",after,afterId,50);
   if(recent.size()>50)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
   var next=recent.stream().findFirst();
   if(next.isEmpty())return Optional.empty();var r=next.get();if(r.status()!=SyncStatus.SUCCEEDED||!r.snapshotComplete())throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
   return Optional.of(new WorkflowRuntimeService.Batch(r.id(),r.completedAt(),r.startedAt(),samples.read(p,source,r.id())));
  },new WorkflowEntityOutput(wiring.writer()),Clock.systemUTC(),new WorkflowHostRuntimeService.Sources(){
   public void require(WorkflowStore.Session s,Principal p,WorkflowDefinition.Source source){connections.service().workflowConfiguration(s,p,source,true);}
   public void requireMetadata(WorkflowStore.Session s,Principal p,WorkflowDefinition.Source source){connections.service().workflowConfiguration(s,p,source,false);}
   public WorkflowHostScan.Page read(Principal p,WorkflowDefinition.Source source,String cursor){var c=connections.service().workflowConfiguration(p,source);return registered.hostPage(p,source.instanceId(),c.endpoint().pin(),c.credentialPin(),c.hostGroupIds(),cursor);}
  });
 }
 WorkflowRuntimeService runtime(){return runtime;}
 WorkflowService service(){return service;}
 public com.acme.opsweave.catalog.application.ModelReferenceService.Workflows modelReferences(){return new WorkflowModelReferenceService(wiring.workflows(),service);}
 private ModelDefinition model(Principal p,WorkflowDefinition.Target target){catalog.authorize(p,false);return builtin.definitions().stream().filter(m->m.ref().equals(target.ref())).findFirst().orElseGet(()->catalog.find(p,target.ref()).definition());}
 private Principal principal(){var p=principals.requirePrincipal();service.authorize(p);return p;}
 @GetMapping public Object list(){var p=principal();var models=new ArrayList<ModelDefinition>(builtin.definitions().stream().filter(m->m.kind()==ModelDefinition.Kind.ENTITY).toList());boolean canReadCatalog=new com.acme.opsweave.identity.domain.Authorizer().decide(p,new com.acme.opsweave.identity.domain.ResourceRef(p.tenantId(),"catalog","*"),com.acme.opsweave.identity.domain.Permission.ENTITY_READ).allowed();if(!canReadCatalog)models.clear();var custom=canReadCatalog?catalog.published(p):null;if(custom!=null)models.addAll(custom.items().stream().map(com.acme.opsweave.catalog.api.ModelCatalogStore.Entry::definition).filter(m->m.kind()==ModelDefinition.Kind.ENTITY).toList());
  return Map.of("schemaVersion","2.0","operatorCatalog",WorkflowJson.operatorCatalog(),"storage",wiring.label(),"drafts",entries(service.drafts(p)),"published",entries(service.versions(p)),"models",models.stream().map(m->Map.of("definition",CatalogJson.wire(m),"digest",m.digest())).toList(),"modelsTruncated",custom!=null&&custom.truncated(),"zabbixSource",Map.of("instanceId",Objects.toString(properties.zabbix().sourceInstanceId(),""),"mode",Objects.toString(properties.zabbix().mode(),"closed")),"runs",runs(service.runs(p)),"metricMappings",metricMappings.definitions().stream().filter(m->new com.acme.opsweave.identity.domain.Authorizer().decide(p,com.acme.opsweave.identity.domain.ResourceRef.metric(p.tenantId(),m.metricKey()),com.acme.opsweave.identity.domain.Permission.METRIC_READ).allowed()).map(com.acme.opsweave.platform.telemetry.MetricMappingJson::definition).toList());
 }
 @GetMapping("/runs/{id}")public Object run(@PathVariable UUID id,HttpServletRequest request){if(request.getQueryString()!=null)throw new IllegalArgumentException();return WorkflowJson.detail(service.run(principal(),id));}
 private Object runs(WorkflowService.Page<WorkflowStore.Run> page){return Map.of("items",page.items().stream().map(WorkflowJson::summary).toList(),"truncated",page.truncated());}
 private Object entries(WorkflowService.Page<WorkflowStore.Entry> page){return Map.of("items",page.items().stream().map(WorkflowJson::wire).toList(),"truncated",page.truncated());}
 @PostMapping(value="/drafts",consumes="application/json")public Object save(HttpServletRequest request)throws IOException {if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principal();var n=CatalogJson.read(request);fields(n,Set.of("definition","layout","expectedEditVersion"));var d=WorkflowJson.definition(n.get("definition"));checkSource(d);return WorkflowJson.wire(service.save(p,d,WorkflowJson.layout(n.get("layout")),integer(n,"expectedEditVersion",null)));}
 @PostMapping(value="/comparisons",consumes="application/json")public Object compare(HttpServletRequest request)throws IOException {if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principal();var n=CatalogJson.read(request);fields(n,Set.of("base","candidate"));var result=service.compare(p,comparisonRef(n.get("base")),comparisonRef(n.get("candidate")));return Map.of("schemaVersion","2.0","base",result.base(),"candidate",result.candidate(),"comparedAt",result.comparedAt(),"changes",result.changes());}
 private com.acme.opsweave.integration.domain.WorkflowComparison.Reference comparisonRef(tools.jackson.databind.JsonNode n){fields(n,Set.of("id","revision","state","editVersion","digest"));return new com.acme.opsweave.integration.domain.WorkflowComparison.Reference(text(n,"id"),integer(n,"revision",null),text(n,"state"),integer(n,"editVersion",null),text(n,"digest"));}
 private void checkSource(WorkflowDefinition d){if(d.source().configuration()==null&&d.source().kind().equals("ZABBIX_HOST")&&!d.source().instanceId().equals(properties.zabbix().sourceInstanceId()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
 @GetMapping("/drafts/{id}/{revision}")public Object draft(@PathVariable String id,@PathVariable int revision){return WorkflowJson.wire(service.draft(principal(),id,revision));}
 @GetMapping("/versions/{id}/{revision}")public Object version(@PathVariable String id,@PathVariable int revision){return WorkflowJson.wire(service.version(principal(),id,revision));}
 @PostMapping(value="/publish",consumes="application/json")public Object publish(HttpServletRequest request)throws IOException {if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principal();var n=CatalogJson.read(request);fields(n,Set.of("id","revision","editVersion","digest","previewId"));return WorkflowJson.wire(service.publish(p,text(n,"id"),integer(n,"revision",null),integer(n,"editVersion",null),text(n,"digest"),UUID.fromString(text(n,"previewId"))));}
 @PostMapping(value="/{mode:preview|run}",consumes="application/json")public Object evaluate(@PathVariable String mode,HttpServletRequest request)throws IOException {if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principal();var n=CatalogJson.read(request);fields(n,Set.of("id","revision","editVersion","digest","samples","syncRunId","dryRun"));if(!n.has("dryRun")||!n.get("dryRun").isBoolean()||!n.get("dryRun").asBoolean())throw new IllegalArgumentException();
  var result=service.evaluate(p,text(n,"id"),integer(n,"revision",null),integer(n,"editVersion",null),text(n,"digest"),mode.equals("run"),n.has("samples")?WorkflowJson.samples(n.get("samples")):null,n.has("syncRunId")?UUID.fromString(text(n,"syncRunId")):null);
  if(CatalogJson.JSON.writeValueAsBytes(result).length>1048576)throw new IllegalArgumentException("Workflow report too large");return result;
 }
}
