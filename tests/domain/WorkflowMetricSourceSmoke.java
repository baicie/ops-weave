import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.telemetry.domain.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Synthetic server-owned discovery and raw samples; no upstream connection or output write claim. */
public final class WorkflowMetricSourceSmoke {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("Metric source "+checks);}
 static void fail(WorkflowFailure.Code c,Runnable work){checks++;try{work.run();}catch(WorkflowFailure e){if(e.code()==c)return;throw new AssertionError(e.code());}throw new AssertionError("Expected "+c);}
 static void invalid(Runnable work){checks++;try{work.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected invalid source");}
 static WorkflowDefinition flow(String id,WorkflowDefinition.Source source,MappingDefinition mapping){var d=WorkflowStandardMetricSmoke.flow(id,mapping);return new WorkflowDefinition(d.id(),1,d.name(),source,d.target(),d.nodes(),d.edges());}
 public static void main(String[] args)throws Exception{
  var clock=Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"),ZoneOffset.UTC);var store=new InMemoryWorkflowStore();var cat=new SourceConnectionSmoke.Catalog();
  var p=new Principal(new SubjectId("metric-source-fixture"),cat.tenant,Set.of(Permission.SOURCE_SYNC,Permission.SOURCE_CONFIGURE,Permission.METRIC_READ,Permission.ENTITY_READ),ResourceScope.tenantWide());
  var credentials=new SourceCredentialService(store,new AesGcmCredentialProtector("fixture-key",Map.of("fixture-key",new byte[32])),clock);SourceCredential credential;
  var credentialId=UUID.randomUUID();try(var w=new SourceCredentialService.Write(credentialId,0,"Fixture credential","ACTIVE","fixture-value".toCharArray())){credential=credentials.write(p,credentialId,w,true).credential();}
  var connections=new SourceConnectionService(store,new SourceEndpointService(cat),credentials,clock);var id=UUID.randomUUID();var created=connections.write(p,id,SourceConnectionSmoke.command(id,0,"Fixture metric connection",cat.endpoint,credential.pin()),true);
  var c=created.connection();var host=new WorkflowDefinition.Source("ZABBIX_HOST",created.receipt().instance().source().instanceId(),new WorkflowDefinition.ConfigurationPin(id,1,c.connectionDigest()));
  var m=MappingDocumentParser.parse(Files.readString(Path.of("extensions/mappings/zabbix-cpu-user.yaml")));
  var item=new SourceMetricDiscovery.Item("50740","10683",m.itemKeyExact(),"Fixture CPU","%","FLOAT","MAPPED",SourceMetricDiscovery.Mapping.from(m));
  var discovery=new SourceMetricDiscovery(List.of(item),true,"FIRST_PAGE_MATCH","READ_VERIFIED",SourceMetricDiscovery.digest(List.of(item)));
  var pending=SourceInspection.pending(id,UUID.randomUUID(),"DISCOVER_METRICS",created.receipt().instance(),clock.instant());var r=pending.complete(clock.instant(),null,null,discovery);
  store.transaction(p.tenantId(),s->{s.addSourceInspection(p.subjectId().value(),pending);s.finishSourceInspection(p.subjectId().value(),r);return null;});
  var service=new WorkflowMetricSourceService(store,connections,clock);var listed=service.list(p,host);check(listed.items().size()==1&&!listed.truncated());var source=listed.items().getFirst().source();check(source.metric().equals(WorkflowMetricSourcePin.from(r.requestId(),item)));
  check(store.transaction(p.tenantId(),s->service.require(s,p,source,true)).equals(item));
  var source2=new WorkflowDefinition.Source(source.kind(),source.instanceId(),source.configuration(),new WorkflowMetricSourcePin(r.requestId(),"50741",item.hostId(),item.sourceKey(),item.sourceUnit(),item.sourceValueType(),WorkflowMetricSourcePin.digest(r.requestId(),"50741",item.hostId(),item.sourceKey(),item.sourceUnit(),item.sourceValueType())));
  fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->store.transaction(p.tenantId(),s->service.require(s,p,source2,true)));
  invalid(()->new WorkflowMetricSourcePin(r.requestId(),item.itemId(),item.hostId(),item.sourceKey(),item.sourceUnit(),item.sourceValueType(),"sha256:"+"0".repeat(64)));
  invalid(()->new WorkflowDefinition.Source("ZABBIX_METRIC",source.instanceId(),source.configuration()));
  invalid(()->new WorkflowDefinition.Source("ZABBIX_HOST",source.instanceId(),source.configuration(),source.metric()));
  invalid(()->{var f=flow("metric-wrong-target",source,m);new WorkflowDefinition(f.id(),1,f.name(),source,new WorkflowDefinition.Target(null,1,null,"METRIC"),f.nodes(),f.edges());});
  var noMetric=new Principal(p.subjectId(),p.tenantId(),Set.of(Permission.SOURCE_SYNC,Permission.ENTITY_READ),ResourceScope.tenantWide());check(service.list(noMetric,host).items().isEmpty());fail(WorkflowFailure.Code.FORBIDDEN,()->store.transaction(p.tenantId(),s->service.require(s,noMetric,source,true)));
  var noEntity=new Principal(p.subjectId(),p.tenantId(),Set.of(Permission.SOURCE_SYNC,Permission.METRIC_READ),ResourceScope.tenantWide());check(service.list(noEntity,host).items().isEmpty());fail(WorkflowFailure.Code.FORBIDDEN,()->store.transaction(p.tenantId(),s->service.require(s,noEntity,source,true)));
  var other=new Principal(new SubjectId("other-owner"),p.tenantId(),p.permissions(),ResourceScope.tenantWide());fail(WorkflowFailure.Code.NOT_FOUND,()->service.list(other,host));
  var expired=new WorkflowMetricSourceService(store,connections,Clock.offset(clock,Duration.ofHours(1)));check(expired.list(p,host).items().isEmpty());check(store.transaction(p.tenantId(),s->expired.require(s,p,source,false)).equals(item));
  var d=flow("metric-source-fixture",source,m);var manual=WorkflowStandardMetricSmoke.flow("metric-source-fixture",m);check(!d.digest().equals(manual.digest()));check(WorkflowComparison.compare(manual,d).stream().anyMatch(x->x.key().equals("itemId")));
  var sources=new WorkflowService.Sources(){public void require(Principal principal,WorkflowDefinition.Source s,com.acme.opsweave.integration.api.WorkflowStore.Session session,boolean available){connections.workflowConfiguration(session,principal,s,available);}public void requireTarget(Principal principal,WorkflowDefinition.Source s,WorkflowDefinition.Target t,com.acme.opsweave.integration.api.WorkflowStore.Session session,boolean available){service.requireTarget(session,principal,s,t,available);}};
  var io=new AtomicInteger();var reader=new AtomicReference<WorkflowService.Samples>((principal,s,b)->{io.incrementAndGet();return new WorkflowService.Batch(List.of(WorkflowStandardMetricSmoke.sample(m,"12.5")),"zabbix-jsonrpc",1,0,false,"SUCCEEDED");});
  var workflow=new WorkflowService(store,(principal,t)->{throw new AssertionError("No entity model");},(principal,s,b)->reader.get().read(principal,s,b),sources,(principal,pin)->m,clock);
  var positions=Map.of("source",new com.acme.opsweave.integration.api.WorkflowStore.Position(0,0),"mapping",new com.acme.opsweave.integration.api.WorkflowStore.Position(0,100),"validate",new com.acme.opsweave.integration.api.WorkflowStore.Position(0,200),"output",new com.acme.opsweave.integration.api.WorkflowStore.Position(0,300));
  var draft=workflow.save(p,d,positions,0);check(io.get()==0);var result=workflow.evaluate(p,d.id(),1,1,d.digest(),false,null,null);check(io.get()==1&&result.receipt().accepted()==1&&!result.evaluation().writesPerformed());check(result.evaluation().rows().getFirst().steps().getLast().values().get("value").equals("0.125"));
  var published=workflow.publish(p,d.id(),1,1,d.digest(),result.receipt().id());check(published.definition().source().equals(source));check(workflow.run(p,result.receipt().id()).trace().source().metric().equals(source.metric()));
  var wrong=new WorkflowDefinition(d.id(),2,d.name(),source,new WorkflowDefinition.Target(null,1,null,"METRIC",new MetricMappingPin(m.id(),2,m.pin().digest()),m.metricKey()),d.nodes(),d.edges());fail(WorkflowFailure.Code.MAPPING_CHANGED,()->workflow.save(p,wrong,positions,0));check(io.get()==1);
  var before=workflow.runs(p).items().size();reader.set((principal,s,b)->{try(var w=new SourceCredentialService.Write(UUID.randomUUID(),1,"Fixture revoked","REVOKED",null)){credentials.write(principal,credentialId,w,false);}return new WorkflowService.Batch(List.of(WorkflowStandardMetricSmoke.sample(m,"12.5")),"zabbix-jsonrpc",1,0,false,"SUCCEEDED");});
  fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->workflow.evaluate(p,d.id(),1,0,d.digest(),true,null,null));check(workflow.runs(p).items().size()==before);check(workflow.version(p,d.id(),1).definition().equals(d));
  System.out.println("Workflow metric source smoke: "+checks+" checks passed");
 }
}
