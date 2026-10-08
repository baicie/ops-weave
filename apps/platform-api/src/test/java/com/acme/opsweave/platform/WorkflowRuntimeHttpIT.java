package com.acme.opsweave.platform;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.*;
import com.acme.opsweave.platform.workflow.WorkflowJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.integration.domain.WorkflowDefinition;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.api.WorkflowStore.Position;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import tools.jackson.databind.JsonNode;

/** Isolated tenant, actual HTTP/PG/asset sink/scheduler; explicit synthetic source only. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=runtime-http-fixture-only-32-characters","opsweave.auth.dev.subject=runtime-author","opsweave.auth.dev.permissions=entity.read,entity.manage,source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=runtime-fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class WorkflowRuntimeHttpIT {
 static final String TENANT="runtime-http-"+UUID.randomUUID();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;
 @Autowired InventoryWiring wiring;
 @Autowired IngestZabbixHostsUseCase hosts;
 @Autowired com.acme.opsweave.integration.application.HostPipelineService pipeline;
 final HttpClient http=HttpClient.newHttpClient();
 HttpResponse<String> call(String path,Object body)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/workflows"+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer runtime-http-fixture-only-32-characters");if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body)));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
 JsonNode success(String path,Object body)throws Exception{var response=call(path,body);assertEquals(200,response.statusCode(),path+": "+response.body());return CatalogJson.JSON.readTree(response.body());}
 WorkflowDefinition graph(boolean source){var model=new BuiltinCatalog().definitions().stream().filter(m->m.id().equals(source?"builtin.host":"builtin.service")).findFirst().orElseThrow();return com.acme.opsweave.integration.domain.WorkflowOperators.builtIn().pin(new WorkflowDefinition("rt-"+UUID.randomUUID(),1,"LOCALTEST runtime DAG",new Source(source?"ZABBIX_HOST":"MANUAL_SAMPLE",source?"runtime-fixture":"manual"),new Target(model.id(),1,model.digest()),List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("map",Type.MAP,"1",source?Map.of("name","hostname","ip","ip"):Map.of("name","name")),new Node("left",Type.TRIM,"1",Map.of()),new Node("right",Type.TRIM,"1",Map.of()),new Node("merge",Type.MERGE,"1",Map.of()),new Node("validate",Type.VALIDATE,"1",Map.of()),new Node("output",Type.OUTPUT,"1",Map.of())),List.of(new Edge("source","map"),new Edge("map","left"),new Edge("map","right"),new Edge("left","merge"),new Edge("right","merge"),new Edge("merge","validate"),new Edge("validate","output"))));}
 Map<String,Object> input(WorkflowDefinition d,int edit,List<Map<String,Object>> values,UUID batch){var result=new LinkedHashMap<String,Object>();result.put("id",d.id());result.put("revision",1);result.put("digest",d.digest());result.put("editVersion",edit);result.put("dryRun",true);if(values!=null)result.put("samples",values);else result.put("syncRunId",batch);return result;}
 void publish(WorkflowDefinition d,List<Map<String,Object>> values,UUID batch)throws Exception{var layout=new LinkedHashMap<String,Position>();for(int i=0;i<d.nodes().size();i++)layout.put(d.nodes().get(i).id(),new Position(100,i*100));success("/drafts",Map.of("definition",WorkflowJson.wire(d),"layout",layout,"expectedEditVersion",0));
  var preview=success("/preview",input(d,1,values,batch));success("/publish",Map.of("id",d.id(),"revision",1,"digest",d.digest(),"editVersion",1,"previewId",preview.get("receipt").get("id").asString()));}
 Map<String,Object> command(WorkflowDefinition d,boolean source){return new LinkedHashMap<>(Map.of("id",d.id(),"revision",1,"digest",d.digest(),"settings",Map.of("identityField",source?"entity_id":"source_id","nameField",source?"hostname":"name")));}
 Principal p(){return new Principal(new SubjectId("runtime-author"),new TenantId(TENANT),Set.of(Permission.ENTITY_READ,Permission.ENTITY_MANAGE,Permission.SOURCE_SYNC),ResourceScope.tenantWide());}
 @Test void dagPublicationActualEntityWriteAndDuplicateExecutionAreDurable()throws Exception{
  var d=graph(false);var values=List.<Map<String,Object>>of(Map.of("source_id","stable-localtest","name"," LOCALTEST runtime HTTP "));publish(d,values,null);var tested=success("/run",input(d,0,values,null));var body=command(d,false);body.put("previewId",tested.get("receipt").get("id").asString());body.put("samples",values);
  var first=success("/runtime/execute",body);assertEquals("SUCCEEDED",first.get("state").asString());assertEquals(1,first.get("entityIds").size());assertEquals(first,success("/runtime/execute",body));assertEquals(first,success("/runtime/executions/"+first.get("id").asString(),null));assertEquals(400,call("/runtime/executions/"+first.get("id").asText()+"?forged=true",null).statusCode());assertEquals(404,call("/runtime/executions/"+UUID.randomUUID(),null).statusCode());var id=new EntityId(UUID.fromString(first.get("entityIds").get(0).asString()));var entity=wiring.query().find(new TenantId(TENANT),id).orElseThrow();assertEquals("LOCALTEST runtime HTTP",entity.name());assertEquals("workflow",entity.attributes().get("source"));assertEquals("MANUAL_SAMPLE",entity.attributes().get("dataMode"));assertEquals(d.digest(),entity.attributes().get("pipelineDigest"));assertTrue(wiring.query().find(new TenantId(TENANT+"-other"),id).isEmpty());var status=success("/runtime",null);assertFalse(status.toString().contains("LOCALTEST runtime HTTP"));assertFalse(status.toString().contains("stable-localtest"));assertFalse(status.toString().contains("Authorization"));body.put("tenantId","forged");assertEquals(400,call("/runtime/execute",body).statusCode());
 }
 @Test void completionCursorExcludesOldHistoryAndKeepsScopeAndFailures(){
  var tenant=new TenantId(TENANT);var runs=wiring.syncRuns();for(int i=0;i<60;i++){var old=runs.start(tenant,"cursor-fixture","host","labeled-fixture");runs.succeed(tenant,old.id(),"hostid-watermark-snapshot");}
  var cursor=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var fresh=runs.start(tenant,"cursor-fixture","host","labeled-fixture");runs.fail(tenant,fresh.id(),"SOURCE_SCAN_UNVERIFIED","offset-scan-attempt");var stored=runs.find(tenant,fresh.id()).orElseThrow();
  assertEquals(List.of(stored),runs.completedAfter(tenant,"cursor-fixture","host",cursor,new UUID(0,0),50));assertTrue(runs.completedAfter(tenant,"cursor-fixture","host",stored.completedAt(),stored.id(),50).isEmpty());assertTrue(runs.completedAfter(new TenantId(TENANT+"-other"),"cursor-fixture","host",java.time.Instant.EPOCH,new UUID(0,0),50).isEmpty());assertEquals(51,runs.completedAfter(tenant,"cursor-fixture","host",java.time.Instant.EPOCH,new UUID(0,0),50).size());
 }
 @Test void startedTaskProcessesNewFixtureBatchAndStopPreventsNextBatch()throws Exception{
  var d=graph(true);var before=hosts.execute(p(),"runtime-fixture");assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED,before.kind());
  var source=pipeline.preview(p(),before.syncRunId(),com.acme.opsweave.integration.domain.PipelineDefinition.zabbixHostV1(),5);
  var records=source.rows().stream().map(row->{var h=row.candidate();return Map.<String,Object>of("name",h.name(),"ip",h.ip(),"lifecycle",h.lifecycle(),"entity_id",h.entityId());}).toList();
  var model=new BuiltinCatalog().definitions().stream().filter(m->m.id().equals("builtin.host")).findFirst().orElseThrow();var evaluated=com.acme.opsweave.integration.domain.WorkflowEvaluation.evaluate(d,model,records);assertEquals(2,evaluated.accepted());assertEquals(2,com.acme.opsweave.integration.domain.WorkflowTrace.metadata(evaluated).size());
  publish(d,null,before.syncRunId());var start=command(d,true);start.put("expectedGeneration",0);start.put("requestId",UUID.randomUUID());var running=success("/runtime/start",start).get("task");assertEquals("RUNNING",running.get("state").asString());var sync=hosts.execute(p(),"runtime-fixture");assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED,sync.kind());
  long deadline=System.nanoTime()+Duration.ofSeconds(16).toNanos();JsonNode status=null;boolean processed=false;while(System.nanoTime()<deadline){status=success("/runtime",null);for(var execution:status.get("executions"))if(execution.get("workflowId").asString().equals(d.id())&&execution.get("syncRunId").asString().equals(sync.syncRunId().toString())){assertEquals("fixture",execution.get("origin").asString());assertEquals("SUCCEEDED",execution.get("state").asString());assertTrue(execution.get("entityIds").size()>0);processed=true;}if(processed)break;Thread.sleep(200);}assertTrue(processed,status==null?"No status":status.toString());
  start.put("expectedGeneration",1);start.put("requestId",UUID.randomUUID());assertEquals("STOPPED",success("/runtime/stop",start).get("task").get("state").asString());var next=hosts.execute(p(),"runtime-fixture");assertEquals(IngestZabbixHostsUseCase.SyncOutcome.Kind.COMPLETED,next.kind());Thread.sleep(5500);for(var execution:success("/runtime",null).get("executions"))assertNotEquals(next.syncRunId().toString(),execution.get("syncRunId").isNull()?"":execution.get("syncRunId").asString());assertEquals(200,call("/runtime/stop",start).statusCode());start.put("expectedGeneration",2);assertEquals(409,call("/runtime/stop",start).statusCode());
 }
}
