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
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true", "opsweave.auth.dev.token=incident-test-token-not-for-other-use",
    "opsweave.auth.dev.subject=incident-user", "opsweave.auth.dev.tenant=tenant-incident-http",
    "opsweave.auth.dev.permissions=source.sync,entity.read,incident.read,incident.manage", "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=zabbix-1", "opsweave.inventory.store=memory"
})
class IncidentHttpIT {
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String TOKEN = "incident-test-token-not-for-other-use";
    @Test void importsOnceReadsTimelineAndAppliesExplicitIdempotentStatusChange() throws Exception {
        ok(call("POST", "/api/v1/integrations/zabbix/hosts/sync", null, true));
        Map<String,Object> query = new LinkedHashMap<>(); query.put("from", Instant.parse("2026-09-21T11:59:00Z").getEpochSecond()); query.put("till", Instant.parse("2026-09-21T12:59:00Z").getEpochSecond()); query.put("limit", 25); query.put("afterEventId", null);
        JsonNode imported = ok(call("POST", "/api/v1/integrations/zabbix/problems/ingest", query, true));
        assertEquals(2, imported.get("createdIncidents").asInt()); assertEquals(0, imported.get("unmappedHosts").asInt());
        assertEquals("labeled-fixture", imported.get("dataMode").asString());
        assertEquals(0, ok(call("POST", "/api/v1/integrations/zabbix/problems/ingest", query, true)).get("createdIncidents").asInt());
        JsonNode first = ok(call("GET", "/api/v1/incidents?limit=1", null, true)); String id = first.get("items").get(0).get("id").asString();
        assertEquals(id, first.get("nextCursor").asString()); assertEquals(1, ok(call("GET", "/api/v1/incidents?limit=1&after=" + id, null, true)).get("items").size());
        JsonNode detail = ok(call("GET", "/api/v1/incidents/" + id, null, true));
        assertEquals(1, detail.get("record").get("problems").size()); assertEquals(1, detail.get("record").get("problems").get(0).get("entities").size());
        assertTrue(detail.get("record").get("gaps").toString().contains("LOGS_NOT_CONNECTED"));
        var change = Map.of("expectedVersion", 1, "target", "INVESTIGATING", "requestKey", UUID.randomUUID().toString());
        JsonNode transitioned = ok(call("POST", "/api/v1/incidents/" + id + "/transitions", change, true));
        assertEquals(2, transitioned.get("version").asInt()); assertEquals(transitioned, ok(call("POST", "/api/v1/incidents/" + id + "/transitions", change, true)));
        assertEquals(409, call("POST", "/api/v1/incidents/" + id + "/transitions", Map.of("expectedVersion", 1, "target", "MITIGATED", "requestKey", UUID.randomUUID().toString()), true).statusCode());
        detail = ok(call("GET", "/api/v1/incidents/" + id, null, true));
        Path artifacts = Path.of("../../.tmp/incident-http"); Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("problem-ingest-result.json"), imported.toString()); Files.writeString(artifacts.resolve("incident-page.json"), first.toString());
        Files.writeString(artifacts.resolve("incident-detail.json"), detail.toString()); Files.writeString(artifacts.resolve("incident-transition-result.json"), transitioned.toString());
    }
    @Test void rejectsIdentityOverridesMalformedBodiesAndUnboundedReads() throws Exception {
        assertEquals(401, call("GET", "/api/v1/incidents", null, false).statusCode());
        for (String path : List.of("/api/v1/incidents?limit=101", "/api/v1/incidents?status=BAD", "/api/v1/incidents?limit=1&limit=2", "/api/v1/incidents?after=1-1-1-1-1", "/api/v1/incidents/not-a-uuid")) assertEquals(400, call("GET", path, null, true).statusCode());
        assertEquals(404, call("GET", "/api/v1/incidents/" + UUID.randomUUID(), null, true).statusCode());
        assertEquals(400, call("POST", "/api/v1/integrations/zabbix/problems/ingest", Map.of("tenantId", "other", "from", 1, "till", 2, "limit", 25), true).statusCode());
        assertEquals(400, call("POST", "/api/v1/incidents/" + UUID.randomUUID() + "/transitions", Map.of("expectedVersion", new java.math.BigInteger("18446744073709551617"), "target", "INVESTIGATING", "requestKey", UUID.randomUUID().toString()), true).statusCode());
    }
    private JsonNode ok(HttpResponse<String> response) { assertEquals(200, response.statusCode(), response.body()); return json.readTree(response.body()); }
    private HttpResponse<String> call(String method, String path, Object body, boolean auth) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (auth) request.header("Authorization", "Bearer " + TOKEN);
        if (method.equals("POST")) request.header("Content-Type", "application/json").POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))); else request.GET();
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
