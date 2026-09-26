package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.bind-loopback-only=true","opsweave.auth.dev.token=spend-test-token-not-for-real-use-only",
    "opsweave.auth.dev.subject=spend-user","opsweave.auth.dev.tenant=spend-http","opsweave.auth.dev.permissions=source.sync,entity.read,metric.read,incident.read,incident.manage,ai.diagnose,ai.insight.read,evidence.read",
    "opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=memory","opsweave.ai.runtime-key=spend-runtime-key-fixture-not-for-real-use"})
class ModelSpendHttpIT {
    @LocalServerPort int port;static final JsonMapper JSON=JsonMapper.builder().build();
    static final Map<String,String> WINDOW=Map.of("from","2026-09-21T11:30:00Z","to","2026-09-21T12:30:00Z");
    HttpResponse<String> call(String path,Object body,String session,boolean runtime,boolean auth)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if(auth)b.header("Authorization","Bearer spend-test-token-not-for-real-use-only");if(runtime)b.header("X-OpsWeave-Runtime-Key","spend-runtime-key-fixture-not-for-real-use");
        if(session!=null)b.header("X-OpsWeave-Read-Session",session);
        if(body==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body instanceof String s?s:JSON.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(HttpResponse<String> r){assertEquals(200,r.statusCode(),r.body());return JSON.readTree(r.body());}
    JsonNode session()throws Exception {
        ok(call("/api/v1/integrations/zabbix/hosts/sync","",null,false,true));
        var query=new LinkedHashMap<String,Object>();query.put("from",Instant.parse(WINDOW.get("from")).getEpochSecond());query.put("till",Instant.parse(WINDOW.get("to")).getEpochSecond());query.put("limit",25);query.put("afterEventId",null);
        ok(call("/api/v1/integrations/zabbix/problems/ingest",query,null,false,true));
        String id=ok(call("/api/v1/incidents",null,null,false,true)).get("items").get(0).get("id").asString();
        return ok(call("/api/v1/ai/read-sessions",Map.of("incidentId",id,"timeRange",WINDOW,"knowledgeMode","current"),null,false,true));
    }
    Map<String,Object> reserve(String sid){return Map.of("runId",UUID.randomUUID().toString(),"sessionId",sid,"inputDigest","sha256:"+"a".repeat(64),"inputBytes",1000);}
    @Test void diagnosisPermissionsNeverGrantRetentionManagement()throws Exception {
        assertEquals(403,call("/api/v1/ai/retention",null,null,false,true).statusCode());
        assertEquals(403,call("/api/v1/ai/retention/runs","{}",null,true,true).statusCode());
        assertEquals(403,call("/api/v1/ai/retention/runs/"+UUID.randomUUID(),null,null,false,true).statusCode());
    }
    @Test void explicitMockCallIsReservedReportedAndReadableWithoutExposingRuntimeKey()throws Exception {
        var s=session();String sid=s.get("id").asString();var input=reserve(sid);String path="/api/v1/ai/model-calls/reservations";
        assertEquals(400,call(path,input,null,true,true).statusCode());
        var evidence=ok(call("/api/v1/tools/incident.get/2.0.0",Map.of("incidentId",s.get("incidentId").asString()),sid,false,true));
        ok(call("/api/v1/tools/evidence.get/2.0.0",Map.of("evidenceId",evidence.get("data").get("evidence").get("id").asString()),sid,false,true));
        var reserved=ok(call(path,input,null,true,true));assertEquals("RESERVED",reserved.get("record").get("state").asString());assertTrue(reserved.get("record").get("estimatedMicros").isNull());
        assertEquals(409,call(path,input,null,true,true).statusCode());
        var usage=Map.of("inputTokens",0,"outputTokens",0,"cachedInputTokens",0,"source","mock-no-call");
        var report=Map.of("runId",input.get("runId"),"sessionId",sid,"usage",usage);
        var reported=ok(call("/api/v1/ai/model-calls/reports",report,null,true,true));assertEquals("REPORTED",reported.get("record").get("state").asString());assertEquals(0,reported.get("record").get("accountedMicros").asLong());
        assertEquals(reported,ok(call("/api/v1/ai/model-calls/reports",report,null,true,true)));
        assertEquals(reported,ok(call("/api/v1/ai/model-calls/"+input.get("runId"),null,null,false,true)));
        var artifacts=Path.of("../../.tmp/model-spend-http");Files.createDirectories(artifacts);Files.writeString(artifacts.resolve("model-spend-reserved.json"),reserved.toString());Files.writeString(artifacts.resolve("model-spend-result.json"),reported.toString());
    }
    @Test void rejectsMissingAttestationIdentityOverridesAndOverflowBeforeStore()throws Exception {
        var input=new LinkedHashMap<>(reserve(UUID.randomUUID().toString()));String path="/api/v1/ai/model-calls/reservations";
        assertEquals(401,call(path,input,null,true,false).statusCode());assertEquals(403,call(path,input,null,false,true).statusCode());
        input.put("tenantId","forged");assertEquals(400,call(path,input,null,true,true).statusCode());input.remove("tenantId");
        input.put("inputBytes",Long.MAX_VALUE);assertEquals(400,call(path,input,null,true,true).statusCode());
        var usage=Map.of("inputTokens",Long.MAX_VALUE,"outputTokens",1,"cachedInputTokens",0,"source","provider-reported");
        assertEquals(400,call("/api/v1/ai/model-calls/reports",Map.of("runId",input.get("runId"),"sessionId",input.get("sessionId"),"usage",usage),null,true,true).statusCode());
        assertEquals(400,call(path,"{\"runId\":\"a\",\"runId\":\"b\"}",null,true,true).statusCode());
        assertEquals(404,call("/api/v1/ai/model-calls/"+UUID.randomUUID(),null,null,false,true).statusCode());
    }
}
