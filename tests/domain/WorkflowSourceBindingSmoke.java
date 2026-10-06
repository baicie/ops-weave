import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Synthetic immutable metadata and sample port; does not prove a network connection. */
public final class WorkflowSourceBindingSmoke {
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("Workflow binding "+checks);}
 static void fails(WorkflowFailure.Code code,Runnable r){checks++;try{r.run();}catch(WorkflowFailure e){if(e.code()==code)return;throw new AssertionError(e.code());}throw new AssertionError(code);}
 static void rejects(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected closed binding");}
 public static void main(String[] args){
  var clock=Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"),ZoneOffset.UTC);var tenant=new TenantId("binding-fixture");var p=new Principal(new SubjectId("fixture-owner"),tenant,Set.of(Permission.SOURCE_SYNC,Permission.SOURCE_CONFIGURE,Permission.ENTITY_READ,Permission.ENTITY_MANAGE),ResourceScope.tenantWide());var store=new InMemoryWorkflowStore();
  var endpoint=SourceEndpoint.registered("fixture-host","Fixture host","https://192.0.2.10/api_jsonrpc.php");var endpoints=new SourceEndpointService(new SourceEndpointCatalog(){public List<SourceEndpoint> list(TenantId t){return t.equals(tenant)?List.of(endpoint):List.of();}public Optional<SourceEndpoint> find(TenantId t,String id){return list(t).stream().filter(e->e.id().equals(id)).findFirst();}});
  var credentials=new SourceCredentialService(store,new AesGcmCredentialProtector("fixture-key",Map.of("fixture-key",new byte[32])),clock);var credentialId=UUID.randomUUID();SourceCredential credential;try(var write=new SourceCredentialService.Write(credentialId,0,"Fixture secret","ACTIVE","fixture-token".toCharArray())){credential=credentials.write(p,credentialId,write,true).credential();}
  var connections=new SourceConnectionService(store,endpoints,credentials,clock);var id=UUID.randomUUID();var c=connections.write(p,id,new SourceConnectionService.Write(id,0,"Fixture source","",endpoint.pin(),credential.pin(),List.of("42")),true).connection();
  var source=new WorkflowDefinition.Source("ZABBIX_HOST",SourceConnectionConfiguration.physicalId(id),new WorkflowDefinition.ConfigurationPin(id,1,c.connectionDigest()));var base=WorkflowSmoke.flow();var d=new WorkflowDefinition("fixture-binding",1,"Fixture binding",source,base.target(),base.nodes(),base.edges());
  var legacy=new WorkflowDefinition(d.id(),d.revision(),d.name(),new WorkflowDefinition.Source(source.kind(),source.instanceId()),d.target(),d.nodes(),d.edges());check(!legacy.digest().equals(d.digest()));check(WorkflowSmoke.flow().digest().equals(base.digest()));
  rejects(()->new WorkflowDefinition.Source("MANUAL_SAMPLE","manual",source.configuration()));rejects(()->new WorkflowDefinition.ConfigurationPin(id,101,c.connectionDigest()));
  var calls=new AtomicInteger();var revokeOnRead=new AtomicBoolean();WorkflowService.Samples samples=(principal,s,batch)->{check(s.equals(source)&&batch==null);calls.incrementAndGet();if(revokeOnRead.get())credentials.revoke(p,credentialId,UUID.randomUUID(),2,1);return new WorkflowService.Batch(List.of(Map.of("raw_name","Fixture normalized host")),"zabbix-jsonrpc",1,0,false,"SUCCEEDED");};
  var service=new WorkflowService(store,(principal,t)->WorkflowSmoke.MODEL,samples,(principal,s,session,available)->connections.workflowConfiguration(session,principal,s,available),clock);
  var saved=service.save(p,d,WorkflowSmoke.layout(d),0);check(saved.editVersion()==1);
  var forged=new WorkflowDefinition.Source(source.kind(),"fixture-other",source.configuration());fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->connections.workflowConfiguration(p,forged));
  var wrong=new WorkflowDefinition.Source(source.kind(),source.instanceId(),new WorkflowDefinition.ConfigurationPin(id,1,"sha256:"+"0".repeat(64)));fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->connections.workflowConfiguration(p,wrong));
  fails(WorkflowFailure.Code.NOT_FOUND,()->connections.workflowConfiguration(new Principal(p.subjectId(),new TenantId("foreign-fixture"),p.permissions(),ResourceScope.tenantWide()),source));
  var scoped=new Principal(p.subjectId(),tenant,p.permissions(),ResourceScope.of(Set.of(new ResourceRef(tenant,"workflow","*"),ResourceRef.source(tenant,source.instanceId()),new ResourceRef(tenant,"credential",credentialId.toString()))));fails(WorkflowFailure.Code.FORBIDDEN,()->service.evaluate(scoped,d.id(),1,1,d.digest(),false,null,null));check(calls.get()==0);
  rejects(()->service.evaluate(p,d.id(),1,1,d.digest(),false,null,UUID.randomUUID()));rejects(()->service.evaluate(p,d.id(),1,1,d.digest(),false,List.of(Map.of("raw_name","forged")),null));check(calls.get()==0);
  var result=service.evaluate(p,d.id(),1,1,d.digest(),false,null,null);check(result.receipt().accepted()==1&&calls.get()==1);var run=service.run(p,result.receipt().id());check(run.trace().syncRunId()==null&&run.trace().source().equals(source));check(!result.receipt().inputDigest().equals(WorkflowService.inputDigest(List.of(Map.of("raw_name","Fixture normalized host")),null)));
  SourceCredential rotated;try(var write=new SourceCredentialService.Write(UUID.randomUUID(),1,"Fixture secret","ACTIVE","fixture-rotated".toCharArray())){rotated=credentials.write(p,credentialId,write,false).credential();}
  connections.write(p,id,new SourceConnectionService.Write(UUID.randomUUID(),1,"Fixture source","",endpoint.pin(),rotated.pin(),List.of("42")),false);check(connections.workflowConfiguration(p,source).equals(c));
  var published=service.publish(p,d.id(),1,1,d.digest(),result.receipt().id());check(published.definition().source().equals(source));check(published.digest().equals(saved.digest()));
  var instances=new SourceInstanceService(store,(principal,s)->{throw new AssertionError("No legacy connection");},clock);var latest=instances.read(p,id);instances.edit(p,id,new SourceInstanceService.Edit(UUID.randomUUID(),latest.editVersion(),latest.name(),latest.description(),latest.connectionDigest(),"ARCHIVED"));fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->service.evaluate(p,d.id(),1,0,d.digest(),true,null,null));check(calls.get()==1);check(service.version(p,d.id(),1).definition().source().equals(source));var archived=instances.read(p,id);instances.edit(p,id,new SourceInstanceService.Edit(UUID.randomUUID(),archived.editVersion(),archived.name(),archived.description(),archived.connectionDigest(),"ACTIVE"));check(connections.workflowConfiguration(p,source).equals(c));
  var noFixedAdapter=new WorkflowService(store,(principal,t)->WorkflowSmoke.MODEL,samples,clock);fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->noFixedAdapter.version(p,d.id(),1));
  var runtime=new WorkflowRuntimeService(store,(principal,t)->WorkflowSmoke.MODEL,samples,(principal,s,after,cursor)->Optional.empty(),new WorkflowRuntimeService.Output(){public void validate(Principal principal,WorkflowDefinition definition,WorkflowRuntime.Settings settings,List<Map<String,Object>> input,WorkflowEvaluation evaluation){}public String write(Principal principal,WorkflowDefinition definition,WorkflowRuntime.Settings settings,UUID execution,Instant at,Map<String,Object> input,Map<String,Object> values,String origin){throw new AssertionError("No implicit runtime");}},clock);
  // The legacy runtime cannot consume a new fixed source as a retained unpinned batch.
  fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->runtime.control(p,d.id(),1,d.digest(),new WorkflowRuntime.Settings("name","name"),0,true));
  revokeOnRead.set(true);int before=service.runs(p).items().size();fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->service.evaluate(p,d.id(),1,0,d.digest(),true,null,null));check(service.runs(p).items().size()==before);check(service.version(p,d.id(),1).definition().source().equals(source));
  fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->service.publish(p,d.id(),1,1,d.digest(),result.receipt().id()));check(calls.get()==2);
  System.out.println("Workflow source binding smoke: "+checks+" checks passed");
 }
}
