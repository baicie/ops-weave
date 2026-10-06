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

/** Actual HTTP and isolated PostgreSQL, with no upstream reads or asset writes. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=host-schedule-fixture-32-characters","opsweave.auth.dev.subject=host-schedule-author","opsweave.auth.dev.permissions=entity.read,entity.manage,source.sync","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class WorkflowHostScheduleHttpIT {
 static final String TENANT="host-schedule-http-fixture-"+UUID.randomUUID(),ROOT="/api/v1/integrations/workflows/host-schedules";
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;
 HttpResponse<String> call(String path,Object body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+ROOT+path)).timeout(Duration.ofSeconds(20));if(auth)b.header("Authorization","Bearer host-schedule-fixture-32-characters");if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body)));return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());}
 @Test void missingReceiptAndQueryForgeryStayClosed()throws Exception{assertEquals(404,call("/commands/"+UUID.randomUUID(),null,true).statusCode());assertEquals(401,call("/commands/"+UUID.randomUUID(),null,false).statusCode());assertEquals(400,call("/workflows/fixture-host?tenantId=forged",null,true).statusCode());var result=call("/workflows/fixture-host",null,true);assertEquals(200,result.statusCode());assertTrue(CatalogJson.JSON.readTree(result.body()).get("schedule").isNull());}
 @Test void invalidAndUnavailableStartCannotCreateATask()throws Exception{var body=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID(),"id","missing-fixture","revision",1,"digest","sha256:"+"a".repeat(64),"settings",Map.of("identityField","entity_id","nameField","hostname"),"intervalSeconds",60,"expectedGeneration",0));assertEquals(404,call("/start",body,true).statusCode());assertEquals(409,call("/stop",body,true).statusCode());body.put("intervalSeconds",59);assertEquals(400,call("/start",body,true).statusCode());body.put("intervalSeconds",60);body.put("authority",Map.of());assertEquals(400,call("/start",body,true).statusCode());}
}
