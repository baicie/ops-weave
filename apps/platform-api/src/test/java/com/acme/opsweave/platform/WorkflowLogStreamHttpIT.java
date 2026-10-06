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

/** Actual HTTP and isolated PG; these boundary checks never read source or write logs. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=log-stream-http-fixture-only-32-characters","opsweave.auth.dev.subject=log-stream-author","opsweave.auth.dev.permissions=entity.read,log.read,log.write,source.sync","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class WorkflowLogStreamHttpIT {
    static final String TENANT="log-stream-http-fixture-"+UUID.randomUUID(),ROOT="/api/v1/integrations/workflows/log-streams";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));r.add("opsweave.logs.url",()->System.getenv("OPSWEAVE_TEST_LOGS_URL"));r.add("opsweave.logs.username",()->System.getenv("OPSWEAVE_TEST_LOGS_USER"));r.add("opsweave.logs.password",()->System.getenv("OPSWEAVE_TEST_LOGS_PASSWORD"));}
    @LocalServerPort int port;final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> call(String path,Object body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+ROOT+path)).timeout(Duration.ofSeconds(20));if(auth)b.header("Authorization","Bearer log-stream-http-fixture-only-32-characters");if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(body)));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
    @Test void originalReceiptRequiresTrustedSessionAndRejectsIdentityQuery()throws Exception{var id=UUID.randomUUID();assertEquals(404,call("/commands/"+id,null,true).statusCode());assertEquals(401,call("/commands/"+id,null,false).statusCode());assertEquals(400,call("/commands/"+id+"?tenantId=forged",null,true).statusCode());assertEquals(400,call("/commands/1-1-1-1-1",null,true).statusCode());assertEquals(200,call("/workflows/missing-fixture",null,true).statusCode());}
    @Test void unsupportedAndUntrustedControlNeverStartsATask()throws Exception{var body=new HashMap<String,Object>(Map.of("requestId",UUID.randomUUID(),"id","missing-fixture","revision",1,"digest","sha256:"+"a".repeat(64),"expectedGeneration",0));assertEquals(404,call("/start",body,true).statusCode());assertEquals(409,call("/stop",body,true).statusCode());body.put("samples",List.of());assertEquals(400,call("/start",body,true).statusCode());assertEquals(400,call("/workflows/missing-fixture/verification",Map.of("batchId",UUID.randomUUID(),"tenantId","forged"),true).statusCode());assertEquals(400,call("/workflows/missing-fixture/batches/"+UUID.randomUUID()+"/records/after/-1",null,true).statusCode());}
    @Test void fixedRevisionQueryIsBoundedAndRejectsClientScope()throws Exception{assertEquals(404,call("/workflows/missing-fixture?revision=1",null,true).statusCode());assertEquals(401,call("/workflows/missing-fixture?revision=1",null,false).statusCode());for(var query:List.of("revision=0","revision=01","revision=10001","revision=1&tenantId=forged","revision=1&revision=2","revision=1&digest=forged"))assertEquals(400,call("/workflows/missing-fixture?"+query,null,true).statusCode());}
}
