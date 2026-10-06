package com.acme.opsweave.platform;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Actual trusted HTTP boundary, no source or output request. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=replay-http-fixture-only-32-characters","opsweave.auth.dev.subject=replay-http-author","opsweave.auth.dev.permissions=metric.read,entity.read,source.sync","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class WorkflowMetricReplayHttpIT {
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->"replay-http-fixture");r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));r.add("opsweave.metrics.victoria-url",()->System.getenv("OPSWEAVE_TEST_VM_URL"));}
 @LocalServerPort int port;
 HttpResponse<String> call(String path,String method,String body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/workflows/metric-replays"+path)).timeout(Duration.ofSeconds(10));if(auth)b.header("Authorization","Bearer replay-http-fixture-only-32-characters");if(body!=null)b.header("Content-Type","application/json");b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());}
 @Test void independentPermissionAndClosedWireAreEnforcedBeforeIO()throws Exception{
  var digest="sha256:"+"a".repeat(64);var path="/workflows/missing-fixture/versions/1/"+digest+"/plans";
  assertEquals(403,call(path,"GET",null,true).statusCode());assertEquals(401,call(path,"GET",null,false).statusCode());assertEquals(400,call(path+"?tenantId=forged","GET",null,true).statusCode());
  var body="{\"requestId\":\"10000000-0000-4000-8000-000000000136\",\"id\":\"missing-fixture\",\"revision\":1,\"digest\":\""+digest+"\",\"from\":\"2026-10-05T00:00:00Z\",\"till\":\"2026-10-05T00:01:00Z\"}";
  assertEquals(403,call("/plans","POST",body,true).statusCode());assertEquals(400,call("/plans","POST",body.substring(0,body.length()-1)+",\"tenantId\":\"forged\"}",true).statusCode());assertEquals(400,call("/plans","POST",body+body,true).statusCode());
  assertEquals(400,call("/plans/1-1-1-1-1","GET",null,true).statusCode());assertEquals(400,call("/commands/10000000-0000-4000-8000-000000000136/verification?notify=true","POST",null,true).statusCode());
 }
}
