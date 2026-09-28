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
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=source-setup-http-fixture-only-32-characters","opsweave.auth.dev.subject=workflow-author","opsweave.auth.dev.permissions=entity.read,source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SourceSetupHttpIT {
 static final String TENANT="source-setup-http-"+UUID.randomUUID();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;
 final HttpClient http=HttpClient.newHttpClient();
 HttpResponse<String> raw(String path,String body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/sources"+path)).timeout(Duration.ofSeconds(15));if(auth)b.header("Authorization","Bearer source-setup-http-fixture-only-32-characters");if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
 HttpResponse<String> call(String path,Object body)throws Exception{return raw(path,body==null?null:CatalogJson.JSON.writeValueAsString(body),true);}

 Map<String,Object> command()throws Exception{var page=CatalogJson.JSON.readTree(call("",null).body());var type=page.get("types").get(1);var model=page.get("models").get(0);return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"name","Fixture source setup","description","HTTP fixture only","source",Map.of("kind","MANUAL_SAMPLE","instanceId","manual"),"connectionDigest",type.get("connection").get("digest").asString(),"target",Map.of("id",model.get("definition").get("id").asString(),"revision",1,"digest",model.get("digest").asString())));}
 @Test void confirmedSetupReopensAndDuplicateIsIdempotent()throws Exception{var c=command();var r=call("/confirm",c);assertEquals(200,r.statusCode(),r.body());var body=CatalogJson.JSON.readTree(r.body());assertEquals("DRAFT",body.get("workflow").get("state").asString());assertTrue(body.get("workflow").get("preview").isNull());assertEquals(5,body.get("workflow").get("definition").get("nodes").size());assertEquals(body,CatalogJson.JSON.readTree(call("/confirm",c).body()));assertEquals(body,CatalogJson.JSON.readTree(call("/"+c.get("requestId"),null).body()));c.put("name","Changed");assertEquals(409,call("/confirm",c).statusCode());}
 @Test void requestsCannotOverrideIdentityOrConnection()throws Exception{assertEquals(401,raw("",null,false).statusCode());assertEquals(400,call("?tenantId=other",null).statusCode());for(String key:List.of("tenantId","subject","url","credential","execute")){var c=command();c.put(key,"forged");assertEquals(400,call("/confirm",c).statusCode(),key);}var c=command();c.put("connectionDigest","sha256:"+"0".repeat(64));assertEquals(503,call("/confirm",c).statusCode());c=command();c.put("source",Map.of("kind","ZABBIX_HOST","instanceId","unconfigured"));assertEquals(503,call("/confirm",c).statusCode());String json=CatalogJson.JSON.writeValueAsString(command());assertEquals(400,raw("/confirm",json+" {}",true).statusCode());assertEquals(400,raw("/confirm",json.substring(0,json.length()-1)+",\"name\":\"duplicate\"}",true).statusCode());assertEquals(400,raw("/confirm","x".repeat(65537),true).statusCode());assertEquals(404,call("/"+UUID.randomUUID(),null).statusCode());}
 @Test void pageKeepsFixtureAndNoCredentialValues()throws Exception{var r=call("",null);assertEquals(200,r.statusCode());assertEquals("no-store",r.headers().firstValue("Cache-Control").orElse(""));var p=CatalogJson.JSON.readTree(r.body());assertEquals("postgres",p.get("storage").asString());assertEquals("fixture",p.get("types").get(0).get("connection").get("dataMode").asString());assertTrue(p.get("types").get(0).get("connection").get("endpoint").isNull());assertEquals("LEGACY_IMPORT",p.get("types").get(2).get("status").asString());}
}
