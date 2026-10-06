package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Actual trusted HTTP and PG; the uncertain sample is explicitly synthetic metadata. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=sample-recovery-fixture-only-32-characters","opsweave.auth.dev.subject=sample-author","opsweave.auth.dev.permissions=source.sync,log.read,log.write","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class WorkflowSampleRecoveryHttpIT {
 static final String TENANT="sample-http-fixture-"+UUID.randomUUID(),ROOT="/api/v1/integrations/workflows/sample-recovery";
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;@Autowired InventoryWiring wiring;final HttpClient http=HttpClient.newHttpClient();
 HttpResponse<String> call(String path,boolean auth,String method,String body)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+ROOT+path)).timeout(Duration.ofSeconds(15));if(auth)b.header("Authorization","Bearer sample-recovery-fixture-only-32-characters");if(body!=null)b.header("Content-Type","application/json");b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
 WorkflowSampleRecovery.Command original(){
  var nodes=List.of(new WorkflowDefinition.Node("source",WorkflowDefinition.Type.SOURCE,"1",Map.of()),new WorkflowDefinition.Node("map",WorkflowDefinition.Type.MAP,"1",Map.of("eventTime","eventTime","body","body")),new WorkflowDefinition.Node("validate",WorkflowDefinition.Type.VALIDATE,"1",Map.of()),new WorkflowDefinition.Node("output",WorkflowDefinition.Type.OUTPUT,"1",Map.of()));var d=WorkflowOperators.builtIn().pin(new WorkflowDefinition("sample-http-"+UUID.randomUUID().toString().substring(0,8),1,"Synthetic Fixture",new WorkflowDefinition.Source("MANUAL_SAMPLE","manual"),new WorkflowDefinition.Target(null,1,null,"LOG"),nodes,List.of(new WorkflowDefinition.Edge("source","map"),new WorkflowDefinition.Edge("map","validate"),new WorkflowDefinition.Edge("validate","output"))));
  var now=Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);var layout=new HashMap<String,WorkflowStore.Position>();for(var n:nodes)layout.put(n.id(),new WorkflowStore.Position(0,0));var batch=new WorkflowLogOutput.Receipt(UUID.randomUUID(),d.id(),1,d.digest(),UUID.randomUUID(),"sha256:"+"a".repeat(64),now,now,"UNKNOWN",2,0,0,0,2,"OUTPUT_UNCONFIRMED","sha256:"+"b".repeat(64),List.of(0,1));
  wiring.workflows().transaction(new TenantId(TENANT),s->{s.publish(new WorkflowStore.Entry(d,d.digest(),"PUBLISHED",0,layout,now,null),"sample-author");s.addLogOutput("sample-author",batch);return null;});return new WorkflowSampleRecovery.Command(UUID.randomUUID(),d.id(),1,d.digest(),WorkflowSampleRecovery.Kind.LOG_SAMPLE,batch.requestId(),batch.batchDigest(),batch.updatedAt(),true);
 }
 @Test void closureIsIdempotentAndDoesNotReplaceUnknownProof()throws Exception{var c=original();var body=CatalogJson.JSON.writeValueAsString(c);var status="/batches/LOG_SAMPLE/"+c.batchId();assertTrue(CatalogJson.JSON.readTree(call(status,true,"GET",null).body()).get("closure").isNull());var r=call("/abandon",true,"POST",body);assertEquals(200,r.statusCode(),r.body());assertEquals("ABANDONED",CatalogJson.JSON.readTree(r.body()).get("state").asString());assertEquals(r.body(),call("/abandon",true,"POST",body).body());assertEquals(r.body(),call("/commands/"+c.requestId(),true,"GET",null).body());assertEquals(c.requestId().toString(),CatalogJson.JSON.readTree(call(status,true,"GET",null).body()).get("closure").get("requestId").asString());assertEquals("UNKNOWN",wiring.workflows().transaction(new TenantId(TENANT),s->s.logOutput("sample-author",c.batchId()).orElseThrow()).state());assertEquals(409,call("/abandon",true,"POST",body.replace(c.requestId().toString(),UUID.randomUUID().toString())).statusCode());}
 @Test void ackForgeryAndStaleProofNeverCreateClosure()throws Exception{var c=original();var body=CatalogJson.JSON.writeValueAsString(c);for(var field:List.of("tenantId","userId","generation","records","authority"))assertEquals(400,call("/abandon",true,"POST",body.replaceFirst("\\{","{\""+field+"\":\"forged\"," )).statusCode());assertEquals(400,call("/abandon",true,"POST",body.replace(":true",":false")).statusCode());assertEquals(401,call("/abandon",false,"POST",body).statusCode());assertEquals(409,call("/abandon",true,"POST",body.replace(c.expectedUpdatedAt().toString(),c.expectedUpdatedAt().plusNanos(1).toString())).statusCode());assertEquals(404,call("/commands/"+c.requestId(),true,"GET",null).statusCode());}
 @Test void lookupsAreCanonicalImmutableAndRejectQueryIdentity()throws Exception{var c=original();for(var path:List.of("/commands/1-1-1-1-1","/batches/LOG_STREAM/"+c.batchId(),"/commands/"+c.requestId()+"?tenantId=forged"))assertEquals(400,call(path,true,"GET",null).statusCode());for(var path:List.of("/commands/"+c.requestId(),"/batches/LOG_SAMPLE/"+c.batchId()))for(var method:List.of("POST","PUT","PATCH","DELETE")){var r=call(path,true,method,null);assertEquals(405,r.statusCode());assertEquals("GET",r.headers().firstValue("Allow").orElseThrow());}}

 @Test void malformedJsonAndNoncanonicalCalendarTimesAreInvalidRequests()throws Exception{var c=original();var body=CatalogJson.JSON.writeValueAsString(c);for(var malformed:List.of("{", "null", "[]", body.replace(c.expectedUpdatedAt().toString(),"2026-13-05T00:00:00Z"),body.replace(c.expectedUpdatedAt().toString(),"2026-10-05T00:00:00.000Z"),body.replace(c.expectedUpdatedAt().toString(),"+10000-01-01T00:00:00Z")))assertEquals(400,call("/abandon",true,"POST",malformed).statusCode());assertEquals(404,call("/commands/"+c.requestId(),true,"GET",null).statusCode());}
}
