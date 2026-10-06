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

/** Actual loopback HTTP/PG; private synthetic tenant and no upstream collection or model calls. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","opsweave.auth.mode=dev","opsweave.auth.dev.subject=instance-fixture-author","opsweave.auth.dev.permissions=entity.read,source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=instance-fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SourceInstanceHttpIT {
 static final String TOKEN="instance-fixture-"+UUID.randomUUID(),TENANT="instance-http-"+UUID.randomUUID();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.token",()->TOKEN);r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;
 final HttpClient http=HttpClient.newHttpClient();
 HttpResponse<String> raw(String path,String method,String body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));if(auth)b.header("Authorization","Bearer "+TOKEN);if(body!=null)b.header("Content-Type","application/json");b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
 HttpResponse<String> call(String path,String method,Object body)throws Exception{return raw(path,method,body==null?null:CatalogJson.JSON.writeValueAsString(body),true);}
 Map<String,Object> create()throws Exception{var sources=CatalogJson.JSON.readTree(call("/api/v1/integrations/sources","GET",null).body());return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID(),"name","Fixture managed source","description","Synthetic metadata","source",Map.of("kind","MANUAL_SAMPLE","instanceId","manual"),"connectionDigest",sources.get("types").get(1).get("connection").get("digest").asString()));}
 Map<String,Object> edit(Map<String,Object> c,int version,String name,String state){return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID(),"expectedEditVersion",version,"name",name,"description",c.get("description"),"connectionDigest",c.get("connectionDigest"),"state",state));}
 @Test void editsHaveCasOriginalReceiptsAndImmutableConfigurationHistory()throws Exception{
  var c=create();String path="/api/v2/data-sources/"+c.get("requestId");var creation=call("/api/v2/data-sources","POST",c);assertEquals(200,creation.statusCode(),creation.body());assertEquals("no-store",creation.headers().firstValue("Cache-Control").orElse(""));
  var first=CatalogJson.JSON.readTree(creation.body());var e=edit(c,1,"Revised Fixture","ACTIVE");var saved=call(path,"PATCH",e);assertEquals(200,saved.statusCode(),saved.body());var receipt=CatalogJson.JSON.readTree(saved.body());assertEquals(2,receipt.get("receipt").get("instance").get("editVersion").asInt());assertEquals(1,receipt.get("receipt").get("instance").get("configurationRevision").asInt());
  assertEquals(receipt,CatalogJson.JSON.readTree(call(path,"PATCH",e).body()));assertEquals(receipt,CatalogJson.JSON.readTree(call(path+"/commands/"+e.get("requestId"),"GET",null).body()));
  assertEquals(first,CatalogJson.JSON.readTree(call("/api/v2/data-sources","POST",c).body()));assertEquals("Revised Fixture",CatalogJson.JSON.readTree(call(path,"GET",null).body()).get("instance").get("name").asString());
  assertEquals(c.get("name"),CatalogJson.JSON.readTree(call("/api/v1/integrations/sources/"+c.get("requestId"),"GET",null).body()).get("setup").get("name").asString());
  var history=CatalogJson.JSON.readTree(call(path+"/configurations","GET",null).body());assertEquals(1,history.get("items").size());assertEquals(c.get("connectionDigest"),history.get("items").get(0).get("connectionDigest").asString());
  assertEquals(409,call(path,"PATCH",edit(c,1,"Stale writer","ACTIVE")).statusCode());e.put("name","Changed command");assertEquals(409,call(path,"PATCH",e).statusCode());
 }
 @Test void archiveAndRestoreKeepHistoryAndDoNotCreateWorkflows()throws Exception{
  var c=create();assertEquals(200,call("/api/v2/data-sources","POST",c).statusCode());String path="/api/v2/data-sources/"+c.get("requestId");
  assertEquals(200,call(path,"PATCH",edit(c,1,(String)c.get("name"),"ARCHIVED")).statusCode());assertEquals(409,call(path,"PATCH",edit(c,2,"Archived edit","ARCHIVED")).statusCode());
  assertEquals(200,call(path,"PATCH",edit(c,2,(String)c.get("name"),"ACTIVE")).statusCode());var current=CatalogJson.JSON.readTree(call(path,"GET",null).body()).get("instance");assertEquals(3,current.get("editVersion").asInt());assertEquals("ACTIVE",current.get("state").asString());
  assertTrue(CatalogJson.JSON.readTree(call("/api/v1/integrations/sources/"+c.get("requestId")+"/continuation","GET",null).body()).get("workflow").isNull());
 }
 @Test void identityRemoteSettingsInvalidPinsAndBodiesFailClosed()throws Exception{
  var c=create();assertEquals(200,call("/api/v2/data-sources","POST",c).statusCode());String path="/api/v2/data-sources/"+c.get("requestId");
  assertEquals(401,raw(path,"GET",null,false).statusCode());assertEquals(400,call(path+"?tenantId=forged","GET",null).statusCode());assertEquals(404,call("/api/v2/data-sources/"+UUID.randomUUID(),"GET",null).statusCode());
  for(String key:List.of("tenantId","url","credential","secretRef","execute")){var e=edit(c,1,"Invalid","ACTIVE");e.put(key,"forged");assertEquals(400,call(path,"PATCH",e).statusCode(),key);}
  var e=edit(c,1,"Invalid pin","ACTIVE");e.put("connectionDigest","sha256:"+"0".repeat(64));assertEquals(503,call(path,"PATCH",e).statusCode());
  String json=CatalogJson.JSON.writeValueAsString(edit(c,1,"Bounded","ACTIVE"));assertEquals(400,raw(path,"PATCH",json+" {}",true).statusCode());assertEquals(400,raw(path,"PATCH",json.substring(0,json.length()-1)+",\"name\":\"duplicate\"}",true).statusCode());assertEquals(400,raw(path,"PATCH","x".repeat(65537),true).statusCode());
  assertEquals(1,CatalogJson.JSON.readTree(call(path,"GET",null).body()).get("instance").get("editVersion").asInt());
 }
}
