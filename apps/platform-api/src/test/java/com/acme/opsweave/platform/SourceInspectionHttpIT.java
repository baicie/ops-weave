package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Real loopback HTTP/PG with explicit synthetic Host connector; no actual upstream or model calls. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","opsweave.auth.mode=dev","opsweave.auth.dev.subject=inspection-fixture-author","opsweave.auth.dev.permissions=entity.read,source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=inspection-fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SourceInspectionHttpIT {
 static final String TOKEN="inspection-fixture-"+UUID.randomUUID(),TENANT="inspection-http-"+UUID.randomUUID();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.token",()->TOKEN);r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;final HttpClient http=HttpClient.newHttpClient();
 HttpResponse<String> raw(String path,String method,String body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));if(auth)b.header("Authorization","Bearer "+TOKEN);if(body!=null)b.header("Content-Type","application/json");b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
 HttpResponse<String> call(String path,String method,Object body)throws Exception{return raw(path,method,body==null?null:CatalogJson.JSON.writeValueAsString(body),true);}
 Map<String,Object> create()throws Exception{var catalog=CatalogJson.JSON.readTree(call("/api/v1/integrations/sources","GET",null).body());String pin=catalog.get("types").get(0).get("connection").get("digest").asString();var c=Map.<String,Object>of("requestId",UUID.randomUUID(),"name","Fixture inspection source","description","Synthetic source metadata","source",Map.of("kind","ZABBIX_HOST","instanceId","inspection-fixture"),"connectionDigest",pin);assertEquals(200,call("/api/v2/data-sources","POST",c).statusCode());return c;}
 Map<String,Object> command(Map<String,Object> c){return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID(),"configurationRevision",1,"connectionDigest",c.get("connectionDigest")));}
 @Test void authenticatedTestDiscoveryAndOriginalReceiptsStayBounded()throws Exception{
  var c=create();String base="/api/v2/data-sources/"+c.get("requestId");var test=command(c);var response=call(base+"/test","POST",test);assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));
  var first=CatalogJson.JSON.readTree(response.body());assertEquals("COMPLETED",first.get("view").get("inspection").get("state").asString());assertEquals("LABELED_FIXTURE",first.get("view").get("inspection").get("check").get("statusCode").asString());assertEquals("CURRENT",first.get("view").get("validity").asString());
  var alias=command(c);var checked=call(base+"/connection-check","POST",alias);assertEquals(200,checked.statusCode(),checked.body());assertEquals("COMPLETED",CatalogJson.JSON.readTree(checked.body()).get("view").get("inspection").get("state").asText());var checks=call(base+"/connection-checks","GET",null);assertEquals(200,checks.statusCode(),checks.body());assertEquals(2,CatalogJson.JSON.readTree(checks.body()).get("items").size());
  assertEquals(first,CatalogJson.JSON.readTree(call(base+"/test","POST",test).body()));assertEquals(first,CatalogJson.JSON.readTree(call(base+"/inspections/"+test.get("requestId"),"GET",null).body()));assertEquals(409,call(base+"/discover","POST",test).statusCode());
  var discover=command(c);response=call(base+"/discover","POST",discover);assertEquals(200,response.statusCode(),response.body());var result=CatalogJson.JSON.readTree(response.body()).get("view").get("inspection").get("discovery");assertEquals(5,result.get("fields").size());assertEquals(2,result.get("observedRecords").asInt());assertTrue(result.get("complete").asBoolean());assertEquals("FIRST_HOST_PAGE",result.get("scope").asString());assertFalse(response.body().contains("10.0.0."));assertFalse(response.body().contains("zabbix-server"));assertFalse(response.body().contains("secretRef"));
  var metricCommand=command(c);var metadata=call(base+"/discover-metrics","POST",metricCommand);assertEquals(200,metadata.statusCode(),metadata.body());var metricResult=CatalogJson.JSON.readTree(metadata.body()).get("view");assertEquals("CURRENT",metricResult.get("validity").asString());var metrics=metricResult.get("inspection").get("metricDiscovery");assertEquals(2,metrics.get("items").size());assertEquals("LABELED_FIXTURE",metrics.get("scanConsistency").asString());assertEquals("host.cpu.usage.user",metrics.get("items").get(0).get("mapping").get("metricKey").asString());assertEquals("NO_MAPPING",metrics.get("items").get(1).get("mappingStatus").asString());assertEquals(CatalogJson.JSON.readTree(metadata.body()),CatalogJson.JSON.readTree(call(base+"/discover-metrics","POST",metricCommand).body()));assertEquals(409,call(base+"/discover","POST",metricCommand).statusCode());
  assertEquals(4,CatalogJson.JSON.readTree(call(base+"/inspections","GET",null).body()).get("items").size());
 }
 @Test void connectionHistoryAppliesItsKindLimitBeforeFiltering()throws Exception{
  var c=create();String base="/api/v2/data-sources/"+c.get("requestId");
  for(int i=0;i<2;i++)assertEquals(200,call(base+"/connection-check","POST",command(c)).statusCode());
  for(int i=0;i<20;i++)assertEquals(200,call(base+"/discover","POST",command(c)).statusCode());
  var checks=CatalogJson.JSON.readTree(call(base+"/connection-checks","GET",null).body());
  assertEquals(2,checks.get("items").size());
  checks.get("items").forEach(item->assertEquals("TEST",item.get("inspection").get("kind").asText()));
 } @Test void closedRequestsDoNotCreateHalfAnInspection()throws Exception{
  var c=create();String base="/api/v2/data-sources/"+c.get("requestId");
  assertEquals(401,raw(base+"/test","POST",CatalogJson.JSON.writeValueAsString(command(c)),false).statusCode());assertEquals(400,call(base+"/inspections?tenantId=forged","GET",null).statusCode());assertEquals(404,call(base+"/inspections/"+UUID.randomUUID(),"GET",null).statusCode());
  for(String key:List.of("tenantId","sourceId","url","secretRef","credential","cursor","sample","kind","execute")){var n=command(c);n.put(key,"forged");assertEquals(400,call(base+"/test","POST",n).statusCode(),key);}
  var n=command(c);n.put("configurationRevision",2);assertEquals(409,call(base+"/test","POST",n).statusCode());n=command(c);n.put("requestId","1-1-1-1-1");assertEquals(400,call(base+"/test","POST",n).statusCode());
  assertEquals(400,raw(base+"/test","POST","{\"requestId\":\""+UUID.randomUUID()+"\",\"requestId\":\""+UUID.randomUUID()+"\",\"configurationRevision\":1,\"connectionDigest\":\""+c.get("connectionDigest")+"\"}",true).statusCode());assertEquals(400,raw(base+"/test","POST",CatalogJson.JSON.writeValueAsString(command(c))+" {}",true).statusCode());assertEquals(400,raw(base+"/test","POST","x".repeat(65537),true).statusCode());
  assertEquals(0,CatalogJson.JSON.readTree(call(base+"/inspections","GET",null).body()).get("items").size());
 }
 @Test void archivingInvalidatesOldResultsAndPreventsNewReads()throws Exception{
  var c=create();String base="/api/v2/data-sources/"+c.get("requestId");var test=command(c);assertEquals(200,call(base+"/test","POST",test).statusCode());
  assertEquals(200,call(base,"PATCH",Map.of("requestId",UUID.randomUUID(),"expectedEditVersion",1,"name",c.get("name"),"description",c.get("description"),"connectionDigest",c.get("connectionDigest"),"state","ARCHIVED")).statusCode());
  assertEquals("STALE",CatalogJson.JSON.readTree(call(base+"/inspections/"+test.get("requestId"),"GET",null).body()).get("view").get("validity").asString());assertEquals(200,call(base+"/test","POST",test).statusCode());assertEquals(409,call(base+"/test","POST",command(c)).statusCode());assertEquals(1,CatalogJson.JSON.readTree(call(base+"/inspections","GET",null).body()).get("items").size());
 }
}
