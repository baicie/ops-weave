package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.api.WorkflowStore.Position;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.workflow.WorkflowJson;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Real local HTTP and PostgreSQL; metric values are explicitly synthetic preview input. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=standard-metric-fixture-only-32-characters","opsweave.auth.dev.subject=standard-author","opsweave.auth.dev.permissions=source.sync,metric.read","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class WorkflowStandardMetricHttpIT {
 static final String TENANT="standard-http-"+UUID.randomUUID();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;
 final HttpClient http=HttpClient.newHttpClient();
 final MappingRegistry mappings=ClasspathMappingCatalog.load(getClass().getClassLoader());
 HttpResponse<String> call(String path,Object body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/"+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer standard-metric-fixture-only-32-characters");
  if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body)));
  return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
 }
 WorkflowDefinition flow(String id,int revision,MappingDefinition m){return WorkflowOperators.builtIn().pin(new WorkflowDefinition(id,revision,"Fixture standard metric",new Source("MANUAL_SAMPLE","manual"),new Target(null,1,null,"METRIC",m.pin(),m.metricKey()),List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("mapping",Type.MAP,"1",Map.of("timestamp","timestamp","sourceKey","sourceKey","value","value")),new Node("validate",Type.VALIDATE,"1",Map.of()),new Node("output",Type.OUTPUT,"1",Map.of())),List.of(new Edge("source","mapping"),new Edge("mapping","validate"),new Edge("validate","output"))));}
 Map<String,Object> save(WorkflowDefinition d){var layout=new LinkedHashMap<String,Position>();for(var n:d.nodes())layout.put(n.id(),new Position(0,0));return Map.of("definition",WorkflowJson.wire(d),"layout",layout,"expectedEditVersion",0);}
 Map<String,Object> preview(WorkflowDefinition d,Map<String,Object> sample){return Map.of("id",d.id(),"revision",d.revision(),"editVersion",1,"digest",d.digest(),"dryRun",true,"samples",List.of(sample));}
 MappingDefinition cpu(){return mappings.definitions().stream().filter(m->m.metricKey().equals("host.cpu.usage.user")).findFirst().orElseThrow();}
 Map<String,Object> sample(MappingDefinition m,String value){return Map.of("timestamp","2026-10-04T08:00:00+08:00","sourceKey",m.itemKeyExact(),"value",value);}
 Object ref(WorkflowDefinition d,String state,int edit){return Map.of("id",d.id(),"revision",d.revision(),"state",state,"editVersion",edit,"digest",d.digest());}
 @Test void authorizedDirectoryPreviewPublicationAndPgRoundTripKeepFullExecutableDefinition()throws Exception{
  var page=call("workflows",null);assertEquals(200,page.statusCode(),page.body());var p=CatalogJson.JSON.readTree(page.body());assertEquals(0,p.get("models").size());assertEquals(3,p.get("metricMappings").size());
  var m=cpu();assertTrue(page.body().contains(m.pin().digest()));var d=flow("std-"+UUID.randomUUID(),1,m);
  var saved=call("workflows/drafts",save(d));assertEquals(200,saved.statusCode(),saved.body());assertEquals(d,WorkflowJson.decode(saved.body()).definition());
  var response=call("workflows/preview",preview(d,sample(m,"12.5")));assertEquals(200,response.statusCode(),response.body());var result=CatalogJson.JSON.readTree(response.body());assertEquals(1,result.get("receipt").get("accepted").asInt());assertEquals("MANUAL_SAMPLE",result.get("receipt").get("origin").asString());assertFalse(result.get("evaluation").get("writesPerformed").asBoolean());
  var output=result.get("evaluation").get("rows").get(0).get("steps").get(3).get("values");assertEquals(7,output.size());assertEquals("0.125",output.get("value").asString());assertTrue(output.get("value").isString());assertEquals("2026-10-04T00:00:00Z",output.get("timestamp").asString());assertEquals(m.metricKey(),output.get("metricKey").asString());assertEquals("1",output.get("unit").asString());assertEquals("user",output.get("dimensions").get("mode").asString());assertEquals(m.pin().digest(),output.get("mappingPin").get("digest").asString());
  String runId=result.get("receipt").get("id").asString();var trace=call("workflows/runs/"+runId,null);assertEquals(200,trace.statusCode());assertFalse(trace.body().contains("values"));assertFalse(trace.body().contains("0.125"));assertEquals(WorkflowJson.target(CatalogJson.JSON.readTree(trace.body()).get("trace").get("target")),d.target());
  var command=Map.of("id",d.id(),"revision",1,"editVersion",1,"digest",d.digest(),"previewId",runId);var published=call("workflows/publish",command);assertEquals(200,published.statusCode(),published.body());assertEquals(CatalogJson.JSON.readTree(published.body()),CatalogJson.JSON.readTree(call("workflows/publish",command).body()));assertEquals(d,WorkflowJson.decode(call("workflows/versions/"+d.id()+"/1",null).body()).definition());
  var memory=mappings.definitions().stream().filter(x->x.metricKey().equals("host.memory.available.ratio")).findFirst().orElseThrow();var next=flow(d.id(),2,memory);assertEquals(200,call("workflows/drafts",save(next)).statusCode());
  var compare=call("workflows/comparisons",Map.of("base",ref(d,"PUBLISHED",0),"candidate",ref(next,"DRAFT",1)));assertEquals(200,compare.statusCode(),compare.body());assertTrue(compare.body().contains("metricKey"));assertTrue(compare.body().contains("mappingDigest"));assertTrue(compare.body().contains(m.pin().digest()));assertTrue(compare.body().contains(memory.pin().digest()));
 }
 @Test void stalePinsAndMetadataOverridesAreRejectedBeforeAReceiptIsSaved()throws Exception{
  var m=cpu();var d=flow("std-"+UUID.randomUUID(),1,m);var definition=new LinkedHashMap<>(WorkflowJson.wire(d));var request=new LinkedHashMap<>(save(d));request.put("definition",definition);
  for(var target:List.of(Map.of("kind","METRIC","schemaVersion","1.1","metricKey",m.metricKey(),"mappingPin",Map.of("id",m.id(),"revision",1,"digest","sha256:"+"0".repeat(64))),Map.of("kind","METRIC","schemaVersion","1.1","metricKey","host.other","mappingPin",Map.of("id",m.id(),"revision",1,"digest",m.pin().digest())))){
   definition.put("target",target);var response=call("workflows/drafts",request);assertEquals(409,response.statusCode(),response.body());assertEquals("MAPPING_CHANGED",CatalogJson.JSON.readTree(response.body()).get("error").asString());
  }
  assertEquals(404,call("workflows/drafts/"+d.id()+"/1",null).statusCode());
  definition.put("target",Map.of("kind","LOG","schemaVersion","1.1","metricKey",m.metricKey(),"mappingPin",Map.of("id",m.id(),"revision",1,"digest",m.pin().digest())));assertEquals(400,call("workflows/drafts",request).statusCode());
  definition.put("target",WorkflowJson.wire(d.target()));var nodes=new ArrayList<>(d.nodes());nodes.set(1,new Node("mapping",Type.MAP,"1",Map.of("unit","unit")));definition.put("nodes",nodes.stream().map(WorkflowJson::wire).toList());assertEquals(400,call("workflows/drafts",request).statusCode());
 }
 @Test void wrongSourceKeyRangeAndNestedInputCannotPublish()throws Exception{
  var m=cpu();var d=flow("std-"+UUID.randomUUID(),1,m);assertEquals(200,call("workflows/drafts",save(d)).statusCode());
  for(var bad:List.of(sample(m,"101"),Map.<String,Object>of("timestamp","2026-10-04T00:00:00Z","sourceKey","system.cpu.util[,idle]","value","12.5"))){
   var result=call("workflows/preview",preview(d,bad));assertEquals(200,result.statusCode());var r=CatalogJson.JSON.readTree(result.body());assertEquals(1,r.get("receipt").get("rejected").asInt());var pub=call("workflows/publish",Map.of("id",d.id(),"revision",1,"editVersion",1,"digest",d.digest(),"previewId",r.get("receipt").get("id").asString()));assertEquals(409,pub.statusCode());assertTrue(pub.body().contains("PREVIEW_REQUIRED"));
  }
  assertEquals(400,call("workflows/preview",preview(d,Map.of("timestamp","2026-10-04T00:00:00Z","sourceKey",m.itemKeyExact(),"value",Map.of("nested",12.5)))).statusCode());assertEquals(404,call("workflows/versions/"+d.id()+"/1",null).statusCode());
 }
}
