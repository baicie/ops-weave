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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true", "opsweave.auth.dev.token=tool-test-token-not-for-any-other-use",
    "opsweave.auth.dev.subject=tool-user", "opsweave.auth.dev.tenant=tenant-tool-http",
    "opsweave.auth.dev.permissions=source.sync,entity.read,metric.read,incident.read,incident.manage,ai.diagnose,evidence.read",
    "opsweave.zabbix.mode=fixture", "opsweave.zabbix.source-instance-id=zabbix-1", "opsweave.inventory.store=memory"
})
class ToolGatewayHttpIT {
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final Map<String,String> WINDOW = Map.of("from", "2026-09-21T11:30:00Z", "to", "2026-09-21T12:30:00Z");
    @Test void createsBoundedCurrentSessionPersistsEvidenceAndRechecksIt() throws Exception {
        ok(call("POST", "/api/v1/integrations/zabbix/hosts/sync", null, null, true));
        ok(call("POST", "/api/v1/integrations/zabbix/items/sync", null, null, true));
        var query = new LinkedHashMap<String,Object>(); query.put("from", Instant.parse(WINDOW.get("from")).getEpochSecond()); query.put("till", Instant.parse(WINDOW.get("to")).getEpochSecond()); query.put("limit", 25); query.put("afterEventId", null);
        ok(call("POST", "/api/v1/integrations/zabbix/problems/ingest", query, null, true));
        var page = ok(call("GET", "/api/v1/incidents", null, null, true)); String incident = page.get("items").get(0).get("id").asString();
        var session = ok(call("POST", "/api/v1/ai/read-sessions", Map.of("incidentId", incident, "timeRange", WINDOW, "knowledgeMode", "current"), null, true));
        assertEquals("tenant-tool-http", session.get("tenantId").asString()); assertEquals("tool-user", session.get("subjectId").asString()); assertEquals(4, session.get("maxToolCalls").asInt());
        String sid = session.get("id").asString();
        var result = ok(call("POST", "/api/v1/tools/incident.get/2.0.0", Map.of("incidentId", incident), sid, true));
        assertEquals("partial", result.get("status").asString()); assertFalse(result.get("truncated").asBoolean());
        var snapshot = result.get("data"); var evidence = snapshot.get("evidence"); String eid = evidence.get("id").asString();
        assertEquals("current", snapshot.get("knowledgeMode").asString()); assertEquals("labeled-fixture", snapshot.get("dataModes").get(0).asString());
        assertEquals(incident, evidence.get("incidentId").asString()); assertEquals("untrusted_data", evidence.get("trust").asString());
        assertTrue(Instant.parse(evidence.get("availableAt").asString()).isAfter(Instant.parse(WINDOW.get("to")))); // No invented historical availability.
        assertEquals(snapshot, ok(call("GET", "/api/v1/ai/evidence/" + eid, null, null, true)));
        assertEquals(snapshot, ok(call("POST", "/api/v1/tools/evidence.get/2.0.0", Map.of("evidenceId", eid), sid, true)).get("data"));
        var metricInput = Map.of("incidentId", incident, "metric", "host.cpu.usage.user", "timeRange", WINDOW, "maxPoints", 500);
        assertEquals(503, call("POST", "/api/v1/tools/metric.summary/2.0.0", metricInput, sid, true).statusCode()); // VM unconfigured: never fake empty success.
        ok(call("POST", "/api/v1/tools/incident.get/2.0.0", Map.of("incidentId", incident), sid, true));
        assertEquals(429, call("POST", "/api/v1/tools/incident.get/2.0.0", Map.of("incidentId", incident), sid, true).statusCode());
        Path artifacts = Path.of("../../.tmp/tool-http"); Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("tool-read-session.json"), session.toString()); Files.writeString(artifacts.resolve("platform-evidence.json"), snapshot.toString()); Files.writeString(artifacts.resolve("tool-result.json"), result.toString());
    }
    @Test void rejectsAuthOverridesHistoricalModeAndInvalidLimits() throws Exception {
        var id = UUID.randomUUID().toString(); var body = Map.of("incidentId", id, "timeRange", WINDOW, "knowledgeMode", "current");
        assertEquals(401, call("POST", "/api/v1/ai/read-sessions", body, null, false).statusCode());
        assertEquals(400, call("POST", "/api/v1/ai/read-sessions", Map.of("incidentId", id, "timeRange", WINDOW, "knowledgeMode", "historical"), null, true).statusCode());
        assertEquals(400, call("POST", "/api/v1/ai/read-sessions", Map.of("incidentId", id, "timeRange", WINDOW, "knowledgeMode", "current", "tenantId", "other"), null, true).statusCode());
        assertEquals(400, call("POST", "/api/v1/tools/incident.get/2.0.0", Map.of("incidentId", id), null, true).statusCode());
        assertEquals(404, call("POST", "/api/v1/tools/incident.get/2.0.0", Map.of("incidentId", id), UUID.randomUUID().toString(), true).statusCode());
        assertEquals(400, call("POST", "/api/v1/tools/metric.summary/2.0.0", Map.of("incidentId", id, "metric", "cpu", "timeRange", WINDOW, "maxPoints", new java.math.BigInteger("18446744073709551617")), UUID.randomUUID().toString(), true).statusCode());
        assertEquals(400, call("GET", "/api/v1/ai/evidence/" + id + "?asOf=2020-01-01", null, null, true).statusCode());
    }
    private JsonNode ok(HttpResponse<String> response) { assertEquals(200, response.statusCode(), response.body()); return json.readTree(response.body()); }
    private HttpResponse<String> call(String method, String path, Object body, String session, boolean authenticated) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (authenticated) b.header("Authorization", "Bearer tool-test-token-not-for-any-other-use"); if (session != null) b.header("X-OpsWeave-Read-Session", session);
        if (method.equals("POST")) b.header("Content-Type", "application/json").POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))); else b.GET();
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
