package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.*;
import com.acme.opsweave.platform.workflow.WorkflowJson;
import com.acme.opsweave.integration.domain.WorkflowDefinition;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.api.WorkflowStore.Position;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Actual local HTTP and PG, explicit fixture inputs, no source/network/model execution. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=workflow-http-fixture-only-32-characters","opsweave.auth.dev.subject=workflow-author","opsweave.auth.dev.permissions=entity.read,source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class WorkflowHttpIT {
 static final String TENANT="workflow-http-"+UUID.randomUUID();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;
 final HttpClient http=HttpClient.newHttpClient();
 HttpResponse<String> raw(String path,String body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/workflows"+path)).timeout(Duration.ofSeconds(15));if(auth)b.header("Authorization","Bearer workflow-http-fixture-only-32-characters");if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
 HttpResponse<String> call(String path,Object body)throws Exception{return raw(path,body==null?null:CatalogJson.JSON.writeValueAsString(body),true);}
 WorkflowDefinition definition(){var m=new BuiltinCatalog().definitions().stream().filter(x->x.id().equals("builtin.service")).findFirst().orElseThrow();return com.acme.opsweave.integration.domain.WorkflowOperators.builtIn().pin(new WorkflowDefinition("http-"+UUID.randomUUID(),1,"HTTP fixture workflow",new Source("MANUAL_SAMPLE","manual"),new Target(m.id(),m.revision(),m.digest()),List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("map",Type.MAP,"1",Map.of("raw_name","name","raw_port","port")),new Node("trim",Type.TRIM,"1",Map.of()),new Node("validate",Type.VALIDATE,"1",Map.of()),new Node("output",Type.OUTPUT,"1",Map.of())),List.of(new Edge("source","map"),new Edge("map","trim"),new Edge("trim","validate"),new Edge("validate","output"))));}
 Map<String,Object> save(WorkflowDefinition d,int edit){var layout=new LinkedHashMap<String,Position>();for(int i=0;i<d.nodes().size();i++)layout.put(d.nodes().get(i).id(),new Position(100,100*i));return Map.of("definition",WorkflowJson.wire(d),"layout",layout,"expectedEditVersion",edit);}
 Map<String,Object> evaluation(WorkflowDefinition d,int edit){return Map.of("id",d.id(),"revision",1,"editVersion",edit,"digest",d.digest(),"dryRun",true,"samples",List.of(Map.of("raw_name"," HTTP fixture ","raw_port","8080")));}
 Map<String,Object> publication(WorkflowDefinition d,String receipt){return Map.of("id",d.id(),"revision",1,"editVersion",1,"digest",d.digest(),"previewId",receipt);}
 @Test void savePreviewPublishRunAndReloadArePersistedAndReadonly()throws Exception{
  var page=call("",null);assertEquals(200,page.statusCode(),page.body());assertEquals("no-store",page.headers().firstValue("Cache-Control").orElse(""));var workspace=CatalogJson.JSON.readTree(page.body());assertEquals("postgres",workspace.get("storage").asString());assertEquals(5,workspace.get("models").size());
  var d=definition();var saved=call("/drafts",save(d,0));assertEquals(200,saved.statusCode(),saved.body());assertEquals(WorkflowJson.wire(d).get("id"),WorkflowJson.decode(saved.body()).definition().id());assertEquals(409,call("/drafts",save(d,0)).statusCode());assertEquals(409,call("/publish",publication(d,UUID.randomUUID().toString())).statusCode());
  var result=call("/preview",evaluation(d,1));assertEquals(200,result.statusCode(),result.body());var report=CatalogJson.JSON.readTree(result.body());assertEquals("MANUAL_SAMPLE",report.get("receipt").get("origin").asString());assertEquals(1,report.get("receipt").get("accepted").asInt());assertTrue(report.get("evaluation").get("dryRun").asBoolean());assertFalse(report.get("evaluation").get("writesPerformed").asBoolean());assertEquals("HTTP fixture",report.get("evaluation").get("rows").get(0).get("steps").get(4).get("values").get("name").asString());
  var receipt=report.get("receipt").get("id").asString();var detail=call("/runs/"+receipt,null);assertEquals(200,detail.statusCode(),detail.body());assertEquals("no-store",detail.headers().firstValue("Cache-Control").orElse(""));var trace=CatalogJson.JSON.readTree(detail.body()).get("trace");assertEquals("OK",trace.get("rows").get(0).get("steps").get(4).get("status").asString());assertFalse(detail.body().contains("HTTP fixture"));assertFalse(detail.body().contains("values"));assertEquals(401,raw("/runs/"+receipt,null,false).statusCode());assertEquals(404,call("/runs/"+UUID.randomUUID(),null).statusCode());assertEquals(400,call("/runs/"+receipt+"?tenantId=forged",null).statusCode());var reloaded=call("/drafts/"+d.id()+"/1",null);assertEquals(receipt,WorkflowJson.decode(reloaded.body()).preview().id().toString());var published=call("/publish",publication(d,receipt));assertEquals(200,published.statusCode(),published.body());assertEquals("PUBLISHED",WorkflowJson.decode(published.body()).state());assertEquals(CatalogJson.JSON.readTree(published.body()),CatalogJson.JSON.readTree(call("/versions/"+d.id()+"/1",null).body()));assertEquals(200,call("/publish",publication(d,receipt)).statusCode());assertEquals(409,call("/drafts",save(d,1)).statusCode());
  var run=call("/run",evaluation(d,0));assertEquals(200,run.statusCode(),run.body());var history=CatalogJson.JSON.readTree(call("",null).body()).get("runs").get("items");assertTrue(history.size()>=2);assertFalse(history.toString().contains("raw_name"));assertFalse(history.toString().contains("HTTP fixture"));
 }
 @Test void requestCannotOverrideIdentityOrExecutionAndInvalidModelPinFails()throws Exception{
  assertEquals(401,raw("",null,false).statusCode());var d=definition();var body=new LinkedHashMap<>(save(d,0));body.put("tenantId","other");assertEquals(400,call("/drafts",body).statusCode());var json=CatalogJson.JSON.writeValueAsString(save(d,0));assertEquals(400,raw("/drafts",json+" {}",true).statusCode());assertEquals(400,raw("/drafts",json.substring(0,json.length()-1)+",\"expectedEditVersion\":0}",true).statusCode());assertEquals(400,raw("/drafts","x".repeat(65537),true).statusCode());
  var wrong=new WorkflowDefinition(d.id(),1,d.name(),d.source(),new Target(d.target().id(),1,"sha256:"+"a".repeat(64)),d.nodes(),d.edges());assertEquals(409,call("/drafts",save(wrong,0)).statusCode());assertEquals(200,call("/drafts",save(d,0)).statusCode());
  var command=new LinkedHashMap<>(evaluation(d,1));command.put("dryRun",false);assertEquals(400,call("/preview",command).statusCode());command.put("dryRun",true);command.put("samples",List.of(Map.of("raw_name",Map.of("nested",true))));assertEquals(400,call("/preview",command).statusCode());command.put("samples",Collections.nCopies(6,Map.of("raw_name","n")));assertEquals(400,call("/preview",command).statusCode());command.put("samples",List.of(Map.of("raw_name","n")));command.put("syncRunId",UUID.randomUUID());assertEquals(400,call("/preview",command).statusCode());
 }
 @Test void changedDefinitionLosesReceiptAndSourceCannotBeInvented()throws Exception{
  var d=definition();assertEquals(200,call("/drafts",save(d,0)).statusCode());var preview=call("/preview",evaluation(d,1));assertEquals(200,preview.statusCode(),preview.body());var changed=new WorkflowDefinition(d.id(),1,"New name",d.source(),d.target(),d.nodes(),d.edges());var saved=call("/drafts",save(changed,1));assertEquals(200,saved.statusCode(),saved.body());assertNull(WorkflowJson.decode(saved.body()).preview());assertEquals(409,call("/preview",evaluation(d,1)).statusCode());
  var external=new WorkflowDefinition(d.id(),1,d.name(),new Source("ZABBIX_HOST","unconfigured"),d.target(),d.nodes(),d.edges());assertEquals(503,call("/drafts",save(external,2)).statusCode());
 }
 @Test void comparisonReadsExactPersistedVersionsAndRejectsStaleDrafts()throws Exception{
  var d=definition();assertEquals(200,call("/drafts",save(d,0)).statusCode());
  var preview=call("/preview",evaluation(d,1));assertEquals(200,preview.statusCode(),preview.body());
  var previewId=CatalogJson.JSON.readTree(preview.body()).get("receipt").get("id").asString();
  assertEquals(200,call("/publish",publication(d,previewId)).statusCode());
  var next=new WorkflowDefinition(d.id(),2,"HTTP fixture compared name",d.source(),d.target(),d.nodes(),d.edges());
  assertEquals(200,call("/drafts",save(next,0)).statusCode());
  var base=Map.<String,Object>of("id",d.id(),"revision",1,"state","PUBLISHED","editVersion",0,"digest",d.digest());
  var candidate=Map.<String,Object>of("id",d.id(),"revision",2,"state","DRAFT","editVersion",1,"digest",next.digest());
  var request=Map.of("base",base,"candidate",candidate);
  var original=call("/versions/"+d.id()+"/1",null).body();
  var runCount=CatalogJson.JSON.readTree(call("",null).body()).get("runs").get("items").size();
  var compared=call("/comparisons",request);assertEquals(200,compared.statusCode(),compared.body());
  assertEquals("no-store",compared.headers().firstValue("Cache-Control").orElse(""));
  var report=CatalogJson.JSON.readTree(compared.body());assertEquals("2.0",report.get("schemaVersion").asString());
  assertEquals(CatalogJson.JSON.valueToTree(base),report.get("base"));assertEquals(CatalogJson.JSON.valueToTree(candidate),report.get("candidate"));
  assertEquals(1,report.get("changes").size());var change=report.get("changes").get(0);
  assertEquals("NAME",change.get("section").asString());assertTrue(change.has("nodeId"));assertTrue(change.get("nodeId").isNull());
  assertEquals(d.name(),change.get("before").asString());assertEquals(next.name(),change.get("after").asString());
  assertEquals(original,call("/versions/"+d.id()+"/1",null).body());
  assertEquals(runCount,CatalogJson.JSON.readTree(call("",null).body()).get("runs").get("items").size());
  assertNull(WorkflowJson.decode(call("/drafts/"+d.id()+"/2",null).body()).preview());
  assertEquals(401,raw("/comparisons",CatalogJson.JSON.writeValueAsString(request),false).statusCode());
  assertEquals(400,call("/comparisons?tenantId=forged",request).statusCode());
  var unknown=new LinkedHashMap<String,Object>(request);unknown.put("samples",List.of(Map.of("raw_name","forged")));assertEquals(400,call("/comparisons",unknown).statusCode());
  var stale=new LinkedHashMap<String,Object>(candidate);stale.put("digest","sha256:"+"0".repeat(64));assertEquals(409,call("/comparisons",Map.of("base",base,"candidate",stale)).statusCode());
  stale.put("digest",next.digest());stale.put("editVersion",2);assertEquals(409,call("/comparisons",Map.of("base",base,"candidate",stale)).statusCode());
  stale.put("id","different-workflow");assertEquals(400,call("/comparisons",Map.of("base",base,"candidate",stale)).statusCode());
  stale.put("id",d.id());stale.put("revision",3);assertEquals(404,call("/comparisons",Map.of("base",base,"candidate",stale)).statusCode());
  var layoutOnly=new LinkedHashMap<>(save(next,1));var layout=new LinkedHashMap<String,Position>();for(var n:next.nodes())layout.put(n.id(),new Position(240,400));layoutOnly.put("layout",layout);
  assertEquals(200,call("/drafts",layoutOnly).statusCode());assertEquals(409,call("/comparisons",request).statusCode());
  var current=new LinkedHashMap<String,Object>(candidate);current.put("editVersion",2);assertEquals(200,call("/comparisons",Map.of("base",base,"candidate",current)).statusCode());
  var json=CatalogJson.JSON.writeValueAsString(request);assertEquals(400,raw("/comparisons",json.substring(0,json.length()-1)+",\"base\":"+CatalogJson.JSON.writeValueAsString(base)+"}",true).statusCode());
 }
 @Test void operatorCatalogAndLegacyUpgradePreserveDigestAndPreviewGates()throws Exception{
  var page=CatalogJson.JSON.readTree(call("",null).body());var catalog=page.get("operatorCatalog");
  assertEquals("1.0.0",catalog.get("catalogVersion").asString());assertEquals(11,catalog.get("operators").size());
  assertEquals(com.acme.opsweave.integration.domain.WorkflowOperators.builtIn().catalogDigest(),catalog.get("digest").asString());
  var current=definition();var legacy=new WorkflowDefinition(current.id(),1,current.name(),current.source(),current.target(),current.nodes().stream().map(n->new Node(n.id(),n.type(),n.version(),n.config())).toList(),current.edges());
  assertNotEquals(current.digest(),legacy.digest());assertFalse(CatalogJson.JSON.writeValueAsString(WorkflowJson.wire(legacy)).contains("operatorDigest"));
  assertEquals(200,call("/drafts",save(legacy,0)).statusCode());var report=call("/preview",evaluation(legacy,1));assertEquals(200,report.statusCode(),report.body());
  String receipt=CatalogJson.JSON.readTree(report.body()).get("receipt").get("id").asString();var denied=call("/publish",publication(legacy,receipt));assertEquals(409,denied.statusCode());assertTrue(denied.body().contains("OPERATOR_PIN_REQUIRED"));
  assertEquals(legacy,WorkflowJson.decode(call("/drafts/"+legacy.id()+"/1",null).body()).definition());
  var nodes=new ArrayList<>(current.nodes());var trim=nodes.get(2);nodes.set(2,new Node(trim.id(),trim.type(),trim.version(),trim.config(),"sha256:"+"0".repeat(64)));
  var wrong=new WorkflowDefinition(current.id(),1,current.name(),current.source(),current.target(),nodes,current.edges());var failed=call("/drafts",save(wrong,1));assertEquals(409,failed.statusCode());assertTrue(failed.body().contains("OPERATOR_CHANGED"));
  var upgraded=call("/drafts",save(current,1));assertEquals(200,upgraded.statusCode(),upgraded.body());assertNull(WorkflowJson.decode(upgraded.body()).preview());assertEquals(current,WorkflowJson.decode(upgraded.body()).definition());
  var publication=new LinkedHashMap<>(publication(current,receipt));publication.put("editVersion",2);assertEquals(409,call("/publish",publication).statusCode());
  var preview=call("/preview",evaluation(current,2));assertEquals(200,preview.statusCode(),preview.body());publication.put("previewId",CatalogJson.JSON.readTree(preview.body()).get("receipt").get("id").asString());
  var published=call("/publish",publication);assertEquals(200,published.statusCode(),published.body());assertEquals(current,WorkflowJson.decode(call("/versions/"+current.id()+"/1",null).body()).definition());
  assertEquals(401,raw("",null,false).statusCode());assertEquals(400,call("?tenantId=forged",null).statusCode());
 }

 @Test void modelReferencesReadExactDraftAndPublishedPinsWithoutRuns()throws Exception{
  var d=definition();assertEquals(200,call("/drafts",save(d,0)).statusCode());
  var uri=URI.create("http://127.0.0.1:"+port+"/api/v1/catalog/versions/builtin.service/1/references");var request=HttpRequest.newBuilder(uri).header("Authorization","Bearer workflow-http-fixture-only-32-characters").GET().build();
  var before=call("",null).body();var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());var report=CatalogJson.JSON.readTree(response.body()).get("report");assertTrue(report.get("workflowsAvailable").asBoolean());var items=report.get("references").get("items");boolean found=false;for(var row:items)if(row.get("id").asString().equals(d.id())){found=true;assertEquals("DRAFT",row.get("state").asString());assertEquals(List.of("name","port"),CatalogJson.JSON.convertValue(row.get("fieldIds"),List.class));assertEquals(0,row.get("tasks").size());assertFalse(row.toString().contains("raw_name"));}assertTrue(found);assertEquals(before,call("",null).body());
  var preview=CatalogJson.JSON.readTree(call("/preview",evaluation(d,1)).body());assertEquals(200,call("/publish",publication(d,preview.get("receipt").get("id").asString())).statusCode());var published=http.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,published.statusCode(),published.body());var rows=CatalogJson.JSON.readTree(published.body()).get("report").get("references").get("items");found=false;for(var row:rows)if(row.get("id").asString().equals(d.id())){found=true;assertEquals("PUBLISHED",row.get("state").asString());assertEquals(d.digest(),row.get("digest").asString());}assertTrue(found);
 }
}
