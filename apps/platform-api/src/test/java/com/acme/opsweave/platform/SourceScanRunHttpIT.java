package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import com.acme.opsweave.platform.inventory.SourceReviewJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.TenantId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=scan-run-http-local-test-only-32-characters","opsweave.auth.dev.subject=scan-user","opsweave.auth.dev.tenant=tenant-scan-run-http","opsweave.auth.dev.permissions=entity.read,source.sync","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SourceScanRunHttpIT {
    static final String TENANT="scan-run-http-"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @LocalServerPort int port;@Autowired InventoryWiring wiring;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> call(String path,boolean auth)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(auth)builder.header("Authorization","Bearer scan-run-http-local-test-only-32-characters");
        return http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path,boolean auth)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(auth)builder.header("Authorization","Bearer scan-run-http-local-test-only-32-characters");
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString("")).build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void tracesStoredHostScanAndKeepsUnknownFailureTextOut()throws Exception{
        var sync=post("/api/v1/integrations/zabbix/hosts/sync",true);
        assertEquals(200,sync.statusCode(),sync.body());
        var syncBody=SourceReviewJson.JSON.readTree(sync.body());
        String runId=syncBody.get("syncRunId").asString();
        assertTrue(syncBody.get("snapshotComplete").asBoolean());
        assertEquals("hostid-watermark-snapshot",syncBody.get("scanConsistency").asString());

        var page=call("/api/v1/integrations/zabbix/hosts/runs?limit=5",true);
        assertEquals(200,page.statusCode(),page.body());
        assertEquals("no-store",page.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff",page.headers().firstValue("X-Content-Type-Options").orElse(""));
        var pageBody=SourceReviewJson.JSON.readTree(page.body());
        assertEquals("scan-log",pageBody.get("dataMode").asString());
        assertEquals("host",pageBody.get("objectType").asString());
        assertEquals(TENANT,pageBody.get("tenantId").asString());
        assertEquals("zabbix-1",pageBody.get("sourceInstanceId").asString());
        assertTrue(pageBody.get("items").size()>=1);
        tools.jackson.databind.JsonNode item=null;
        for(var candidate:pageBody.get("items"))if(runId.equals(candidate.get("syncRunId").asString()))item=candidate;
        assertNotNull(item,"the stored scan is traceable by its syncRunId");
        assertEquals(runId,item.get("syncRunId").asString());
        assertEquals("SUCCEEDED",item.get("status").asString());
        assertTrue(item.get("snapshotComplete").asBoolean());
        assertEquals("labeled-fixture",item.get("dataMode").asString());
        assertEquals(2,item.get("accepted").asInt());
        assertFalse(item.get("pipelineVersion").get("id").asString().isBlank());

        var single=call("/api/v1/integrations/zabbix/hosts/runs/"+runId,true);
        assertEquals(200,single.statusCode(),single.body());
        assertEquals(runId,SourceReviewJson.JSON.readTree(single.body()).get("run").get("syncRunId").asString());
        assertEquals("hostid-watermark-snapshot",
            SourceReviewJson.JSON.readTree(single.body()).get("run").get("scanConsistency").asString());
        assertEquals(0,SourceReviewJson.JSON.readTree(call("/api/v1/integrations/zabbix/items/runs",true).body()).get("items").size());

        var first=call("/api/v1/integrations/zabbix/hosts/runs?limit=1",true);
        var firstBody=SourceReviewJson.JSON.readTree(first.body());
        assertEquals(1,firstBody.get("items").size());
        var cursor=firstBody.get("nextCursor");
        if(cursor!=null&&!cursor.isNull()){
            var next=call("/api/v1/integrations/zabbix/hosts/runs?limit=1&after="+cursor.asString(),true);
            assertEquals(200,next.statusCode(),next.body());
            assertNotEquals(firstBody.get("items").get(0).get("syncRunId").asString(),
                SourceReviewJson.JSON.readTree(next.body()).get("items").get(0).get("syncRunId").asString());
        }

        var tenant=new TenantId(TENANT);
        var orphan=wiring.syncRuns().start(tenant,"zabbix-1","host","labeled-fixture");
        wiring.syncRuns().fail(tenant,orphan.id(),"CUSTOM: raw vendor text",com.acme.opsweave.integration.domain.SyncScan.OFFSET_ATTEMPT);
        var trace=call("/api/v1/integrations/zabbix/hosts/runs/"+orphan.id(),true);
        assertEquals(200,trace.statusCode(),trace.body());
        assertFalse(trace.body().contains("CUSTOM"));
        assertFalse(SourceReviewJson.JSON.readTree(trace.body()).get("run").has("failureCode"));
        assertEquals("FAILED",SourceReviewJson.JSON.readTree(trace.body()).get("run").get("status").asString());

        var dir=java.nio.file.Path.of("../../.tmp/source-scan-run-http");java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("source-scan-run-page.json"),page.body());
        java.nio.file.Files.writeString(dir.resolve("source-scan-run-read.json"),single.body());
        java.nio.file.Files.writeString(dir.resolve("source-scan-run-failed.json"),trace.body());
    }

    @Test void rejectsUnknownQueriesMalformedIdsAndUnstoredRuns()throws Exception{
        assertEquals(401,call("/api/v1/integrations/zabbix/hosts/runs",false).statusCode());
        assertEquals(401,call("/api/v1/integrations/zabbix/hosts/runs/"+UUID.randomUUID(),false).statusCode());
        for(String query:List.of("?limit=0","?limit=51","?limit=abc","?after=***","?tenantId=other","?limit=1&limit=2","?sourceInstanceId=other"))
            assertEquals(400,call("/api/v1/integrations/zabbix/hosts/runs"+query,true).statusCode(),query);
        assertEquals(400,call("/api/v1/integrations/zabbix/hosts/runs/not-a-uuid",true).statusCode());
        assertEquals(400,call("/api/v1/integrations/zabbix/hosts/runs/"+UUID.randomUUID()+"?limit=1",true).statusCode());
        assertEquals(404,call("/api/v1/integrations/zabbix/hosts/runs/"+UUID.randomUUID(),true).statusCode());
        assertEquals(404,call("/api/v1/integrations/zabbix/items/runs/"+UUID.randomUUID(),true).statusCode());
        var sync=post("/api/v1/integrations/zabbix/hosts/sync",true);
        assertEquals(200,sync.statusCode(),sync.body());
        String runId=SourceReviewJson.JSON.readTree(sync.body()).get("syncRunId").asString();
        assertEquals(404,call("/api/v1/integrations/zabbix/items/runs/"+runId,true).statusCode());
    }
}
