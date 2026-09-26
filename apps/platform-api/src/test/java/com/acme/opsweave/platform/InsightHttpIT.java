package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true", "opsweave.auth.dev.token=insight-user-token-for-local-testing-only",
    "opsweave.auth.dev.subject=insight-user", "opsweave.auth.dev.tenant=tenant-insight-http",
    "opsweave.auth.dev.permissions=source.sync,entity.read,metric.read,incident.read,ai.diagnose,ai.insight.read,evidence.read",
    "opsweave.zabbix.mode=fixture", "opsweave.zabbix.source-instance-id=zabbix-1", "opsweave.inventory.store=memory",
    "opsweave.ai.runtime-key=insight-runtime-origin-local-test-key-only", "opsweave.ai.model-provider=mock"})
class InsightHttpIT {
    @LocalServerPort int port;
    final JsonMapper json = JsonMapper.builder().build();
    static final Map<String,String> WINDOW = Map.of("from","2026-09-21T11:30:00Z","to","2026-09-21T12:30:00Z");
    @Test void runtimeSubmissionIsIdempotentProtectedAndQueryable() throws Exception {
        ok(call("POST", "/api/v1/integrations/zabbix/hosts/sync", null, null, true, false));
        var query = new LinkedHashMap<String,Object>(); query.put("from",Instant.parse(WINDOW.get("from")).getEpochSecond()); query.put("till",Instant.parse(WINDOW.get("to")).getEpochSecond()); query.put("limit",25); query.put("afterEventId",null);
        ok(call("POST", "/api/v1/integrations/zabbix/problems/ingest", query, null, true, false));
        String incident = ok(call("GET","/api/v1/incidents",null,null,true,false)).get("items").get(0).get("id").asString();
        var diagnosis = new LinkedHashMap<String,Object>(Map.of("runId",UUID.randomUUID().toString(),"incidentId",incident,"question","Current evidence only","knowledgeMode","current","timeRange",WINDOW));
        assertEquals(503, call("POST","/api/v1/ai/diagnoses",diagnosis,null,true,false).statusCode()); // No configured Runtime; no fallback.
        diagnosis.put("tenantId","override"); assertEquals(400, call("POST","/api/v1/ai/diagnoses",diagnosis,null,true,false).statusCode()); diagnosis.remove("tenantId");
        diagnosis.put("asOf","2020-01-01T00:00:00Z"); assertEquals(400, call("POST","/api/v1/ai/diagnoses",diagnosis,null,true,false).statusCode());
        String session = ok(call("POST","/api/v1/ai/read-sessions",Map.of("incidentId",incident,"timeRange",WINDOW,"knowledgeMode","current"),null,true,false)).get("id").asString();
        var first = ok(call("POST","/api/v1/tools/incident.get/2.0.0",Map.of("incidentId",incident),session,true,false));
        var second = ok(call("POST","/api/v1/tools/metric.summary/2.0.0",Map.of("incidentId",incident,"metric","missing.metric","timeRange",WINDOW,"maxPoints",500),session,true,false));
        List<String> ids = List.of(first.get("data").get("evidence").get("id").asString(), second.get("data").get("evidence").get("id").asString());
        for (String id : ids) ok(call("POST","/api/v1/tools/evidence.get/2.0.0",Map.of("evidenceId",id),session,true,false));
        var input = input(session, ids); String run = (String)input.get("runId");
        assertEquals(403, call("POST","/api/v1/ai/insights",input,null,true,false).statusCode());
        assertEquals(401, call("POST","/api/v1/ai/insights",input,null,false,true).statusCode());
        var saved = ok(call("POST","/api/v1/ai/insights",input,null,true,true));
        assertEquals("memory",saved.get("storage").asString()); var record=saved.get("record");
        assertEquals("tenant-insight-http",record.get("tenantId").asString()); assertEquals(incident,record.get("incidentId").asString());
        assertTrue(record.get("insight").get("missingData").toString().contains("METRIC_NOT_FOUND")); assertTrue(record.get("insight").get("limitations").toString().contains("no model inference"));
        assertEquals(saved,ok(call("POST","/api/v1/ai/insights",input,null,true,true)));
        assertEquals(saved,ok(call("GET","/api/v1/ai/insights/"+run,null,null,true,false)));
        input.put("question","changed"); assertEquals(409,call("POST","/api/v1/ai/insights",input,null,true,true).statusCode()); input.put("question","Summarize current evidence");
        input.put("tenantId","other"); assertEquals(400,call("POST","/api/v1/ai/insights",input,null,true,true).statusCode()); input.remove("tenantId");
        input.put("skill",Map.of("id","incident.diagnose","version","2.0.0","digest","sha256:"+"0".repeat(64))); assertEquals(403,call("POST","/api/v1/ai/insights",input,null,true,true).statusCode());
        Path dir=Path.of("../../.tmp/insight-http");Files.createDirectories(dir);Files.writeString(dir.resolve("ai-insight-result.json"),saved.toString());Files.writeString(dir.resolve("ai-insight.json"),record.toString());
    }
    @Test void rejectsUnknownResourceAndQueryOverrides() throws Exception {
        String id=UUID.randomUUID().toString(); assertEquals(404,call("GET","/api/v1/ai/insights/"+id,null,null,true,false).statusCode());
        assertEquals(400,call("GET","/api/v1/ai/insights/"+id+"?asOf=2020-01-01",null,null,true,false).statusCode());
        assertEquals(400,call("POST","/api/v1/ai/insights",Map.of("runId",id),null,true,true).statusCode());
    }
    Map<String,Object> input(String session,List<String> ids) throws Exception {
        String now=Instant.now().toString(); var result=new LinkedHashMap<String,Object>();result.put("runId",UUID.randomUUID().toString());result.put("sessionId",session);result.put("question","Summarize current evidence");
        result.put("asOf",now);result.put("builtAt",now);result.put("completedAt",now);result.put("skill",Map.of("id","incident.diagnose","version","2.0.0","digest",skillDigest()));result.put("model",Map.of("provider","mock-deterministic","name","mock-current-v1"));result.put("evidenceIds",ids);
        result.put("insight",Map.of("summary","Current fixture evidence only","findings",List.of(Map.of("kind","observation","statement","Fixture Incident observed","evidenceRefs",List.of(ids.getFirst()))),"missingData",List.of(),"limitations",List.of()));return result;
    }
    static String skillDigest() throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");for(String file:List.of("skill.json","prompt.md","output.schema.json"))try(var s=InsightHttpIT.class.getResourceAsStream("/skills/incident-diagnosis-current/"+file)){var b=s.readAllBytes();digest.update(ByteBuffer.allocate(8).putLong(b.length).array());digest.update(b);}return "sha256:"+HexFormat.of().formatHex(digest.digest());
    }
    JsonNode ok(HttpResponse<String> response){assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body());}
    HttpResponse<String> call(String method,String path,Object body,String session,boolean user,boolean runtime) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if(user)b.header("Authorization","Bearer insight-user-token-for-local-testing-only");if(runtime)b.header("X-OpsWeave-Runtime-Key","insight-runtime-origin-local-test-key-only");if(session!=null)b.header("X-OpsWeave-Read-Session",session);
        if(method.equals("POST"))b.header("Content-Type","application/json").POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));else b.GET();
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
}
