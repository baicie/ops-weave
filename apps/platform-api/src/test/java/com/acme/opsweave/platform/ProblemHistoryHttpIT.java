package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.incident.domain.IncidentRecord;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.TenantId;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=history-test-token-not-for-other-use",
    "opsweave.auth.dev.subject=reader","opsweave.auth.dev.tenant=tenant-problem-history-http","opsweave.auth.dev.permissions=incident.read,entity.read",
    "opsweave.zabbix.mode=closed","opsweave.inventory.store=memory"})
class ProblemHistoryHttpIT {
    @LocalServerPort int port;
    @Autowired InventoryWiring wiring;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final TenantId TENANT=new TenantId("tenant-problem-history-http");
    private static final Instant NOW=Instant.parse("2026-09-25T00:00:00.123456789Z");
    private ExternalProblem problem(Instant observed,boolean recovery){return new ExternalProblem(TENANT,"zabbix-1","101","201","Historical fixture",3,NOW.minusSeconds(60),observed,List.of("10084"),false,recovery?"301":null,recovery?NOW.minusSeconds(30):null);}
    @Test void readsImmutableInputsWithCutoffCursorAndCurrentVersion() throws Exception {
        var first=problem(NOW,false);var id=IncidentRecord.initialId(first);var received=NOW.plusSeconds(60);
        wiring.incidents().ingest(TENANT,"zabbix-1","labeled-fixture",List.of(first),received);
        wiring.incidents().ingest(TENANT,"zabbix-1","labeled-fixture",List.of(first),received.plusSeconds(1));
        wiring.incidents().ingest(TENANT,"zabbix-1","labeled-fixture",List.of(problem(NOW.plusSeconds(1),true)),received.plusSeconds(1));
        String path="/api/v1/incidents/"+id+"/problem-observations?version=2&from="+NOW.minusSeconds(10).getEpochSecond()+"&till="+NOW.plusSeconds(10).getEpochSecond();
        var before=ok(call(path+"&asOf="+received.minusNanos(1),true));assertEquals(0,before.get("items").size());
        var firstOnly=ok(call(path+"&asOf="+received,true));assertEquals(1,firstOnly.get("items").size());
        assertEquals("ACTIVE",firstOnly.get("items").get(0).get("observation").get("state").asString());
        var page=ok(call(path+"&limit=1",true));assertEquals(1,page.get("items").size());assertFalse(page.get("nextCursor").isNull());
        var next=ok(call(path+"&limit=1&asOf="+page.get("query").get("asOf").asString()+"&after="+page.get("nextCursor").asString(),true));
        assertEquals(1,next.get("items").size());assertTrue(next.get("nextCursor").isNull());assertNotEquals(page.get("items").get(0).get("id"),next.get("items").get(0).get("id"));
        assertEquals(0,ok(call(path+"&source=zabbix-other",true)).get("items").size());
        assertEquals(0,ok(call(path+"&source=zabbix-1&eventId=999",true)).get("items").size());
        assertEquals(409,call(path.replace("version=2","version=1"),true).statusCode());assertEquals(401,call(path,false).statusCode());
        var all=ok(call(path,true));assertEquals(2,all.get("items").size());
        Path artifacts=Path.of("../../.tmp/problem-history-http");Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("problem-observation-page.json"),all.toString());Files.writeString(artifacts.resolve("problem-observation.json"),all.get("items").get(0).toString());
        Files.writeString(artifacts.resolve("problem-observation-query.json"),all.get("query").toString());
    }
    @Test void rejectsUnknownIdentityTimeAndQueryParameters() throws Exception {
        String path="/api/v1/incidents/"+UUID.randomUUID()+"/problem-observations?version=1&from=0&till=60";
        assertEquals(404,call(path,true).statusCode());
        for(String suffix:List.of("&tenantId=foreign","&limit=26","&from=1","&eventId=101","&source=bad%0Avalue","&asOf=not-a-time","&after=1-1-1-1-1"))assertEquals(400,call(path+suffix,true).statusCode());
        assertEquals(400,call(path.replace("till=60","till=9223372036854775807"),true).statusCode());
        assertEquals(400,call(path.replace("till=60","till=2764800"),true).statusCode());
    }
    private JsonNode ok(HttpResponse<String> response){assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body());}
    private HttpResponse<String> call(String path,boolean auth)throws Exception {
        String requestId=UUID.randomUUID().toString();var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("X-OpsWeave-Request-Id",requestId).GET();
        if(auth)request.header("Authorization","Bearer history-test-token-not-for-other-use");
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(requestId,response.headers().firstValue("X-OpsWeave-Request-Id").orElseThrow());assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));return response;
    }
}
