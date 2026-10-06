package com.acme.opsweave.platform;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.api.WorkflowStore.Position;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.workflow.WorkflowJson;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
/** Actual local HTTP, PG receipts and ClickHouse records with synthetic text in an isolated tenant. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=logs-http-fixture-only-32-characters","opsweave.auth.dev.subject=logs-author","opsweave.auth.dev.permissions=source.sync,log.read,log.write","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_LOGS_URL",matches=".+")
class WorkflowLogOutputHttpIT{
 static final String TENANT="logs-http-"+UUID.randomUUID();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));r.add("opsweave.logs.url",()->System.getenv("OPSWEAVE_TEST_LOGS_URL"));r.add("opsweave.logs.username",()->System.getenv("OPSWEAVE_TEST_LOGS_USER"));r.add("opsweave.logs.password",()->System.getenv("OPSWEAVE_TEST_LOGS_PASSWORD"));}
 @LocalServerPort int port;final HttpClient http=HttpClient.newHttpClient();
 HttpResponse<String> call(String path,Object body)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/workflows/"+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer logs-http-fixture-only-32-characters");if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body)));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
 @Test void actualLogsConfirmExactBatchAndMetadataReplaysWithoutRewriting()throws Exception{
  var id="logs-"+UUID.randomUUID();var nodes=List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("map",Type.MAP,"1",TelemetryOutput.fields("LOG").stream().collect(java.util.stream.Collectors.toMap(v->v,v->v))),new Node("validate",Type.VALIDATE,"1",Map.of()),new Node("output",Type.OUTPUT,"1",Map.of()));var d=WorkflowOperators.builtIn().pin(new WorkflowDefinition(id,1,"Fixture actual log storage",new Source("MANUAL_SAMPLE","manual"),new Target(null,1,null,"LOG"),nodes,List.of(new Edge("source","map"),new Edge("map","validate"),new Edge("validate","output"))));var layout=new HashMap<String,Position>();for(var n:d.nodes())layout.put(n.id(),new Position(0,0));assertEquals(200,call("drafts",Map.of("definition",WorkflowJson.wire(d),"layout",layout,"expectedEditVersion",0)).statusCode());
  var time=Instant.now().minusSeconds(1).toString();var samples=List.of(Map.of("eventTime",time,"body","  Fixture quote \" SQL ' emoji 🧵\n<script>untrusted()</script>  ","severityText","INFO","fixtureNumber",0.0000001),Map.of("eventTime",time,"body","Fixture second"));var evaluate=new HashMap<String,Object>(Map.of("id",id,"revision",1,"editVersion",1,"digest",d.digest(),"dryRun",true,"samples",samples));var preview=call("preview",evaluate);assertEquals(200,preview.statusCode());var previewId=CatalogJson.JSON.readTree(preview.body()).get("receipt").get("id").asString();assertEquals(200,call("publish",Map.of("id",id,"revision",1,"editVersion",1,"digest",d.digest(),"previewId",previewId)).statusCode());evaluate.put("editVersion",0);var run=call("run",evaluate);assertEquals(200,run.statusCode());var runId=CatalogJson.JSON.readTree(run.body()).get("receipt").get("id").asString();
  var cap=call("log-outputs/workflows/"+id+"/capability",null);assertEquals(200,cap.statusCode());assertTrue(CatalogJson.JSON.readTree(cap.body()).get("available").asBoolean());
  var requestId=UUID.randomUUID();var command=new HashMap<String,Object>(Map.of("requestId",requestId,"id",id,"revision",1,"digest",d.digest(),"previewId",runId,"samples",samples));var output=call("log-outputs",command);assertEquals(200,output.statusCode());var receipt=CatalogJson.JSON.readTree(output.body());assertEquals("CONFIRMED",receipt.get("state").asString());assertEquals(2,receipt.get("confirmed").asInt());assertFalse(output.body().contains("untrusted"));assertEquals(receipt,CatalogJson.JSON.readTree(call("log-outputs",command).body()));assertEquals(receipt,CatalogJson.JSON.readTree(call("log-outputs/commands/"+requestId,null).body()));
  var data=call("log-outputs/commands/"+requestId+"/records",null);assertEquals(200,data.statusCode());var records=CatalogJson.JSON.readTree(data.body());assertTrue(records.get("complete").asBoolean());assertEquals(2,records.get("records").size());assertEquals(samples.getFirst().get("body"),records.get("records").get(0).get("body").asString());assertTrue(records.get("records").get(1).get("severityText").isNull());assertEquals(receipt,CatalogJson.JSON.readTree(call("log-outputs/commands/"+requestId+"/verification",null).body()));
  command.put("samples",List.of(Map.of("eventTime",time,"body","changed Fixture")));assertEquals(409,call("log-outputs",command).statusCode());command.put("tenantId","forged");assertEquals(400,call("log-outputs",command).statusCode());assertEquals(400,call("log-outputs/commands/"+requestId+"/records?tenantId=forged",null).statusCode());assertEquals(404,call("log-outputs/commands/"+UUID.randomUUID()+"/records",null).statusCode());
 }
}
