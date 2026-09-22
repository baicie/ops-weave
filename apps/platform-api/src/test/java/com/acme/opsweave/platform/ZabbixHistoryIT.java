package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=history-test-token-not-for-real-use", "opsweave.auth.dev.subject=user-demo",
    "opsweave.auth.dev.tenant=tenant-demo", "opsweave.auth.dev.permissions=entity.read,metric.read,source.sync",
    "opsweave.zabbix.mode=fixture", "opsweave.zabbix.source-instance-id=zabbix-1", "opsweave.inventory.store=memory"
})
class ZabbixHistoryIT {
    private static final long START = Instant.parse("2026-09-21T12:00:00Z").getEpochSecond();
    private static final String PATH = "/api/v1/integrations/zabbix/items/20001/history?from=" + START + "&till=" + (START + 10);
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    @LocalServerPort int port;

    @Test
    void authenticatedFixtureReadPaginatesWithoutPersistingPoints() throws Exception {
        assertEquals(401, call("GET", PATH, false).statusCode());
        assertEquals(400, call("GET", PATH + "&tenantId=other", true).statusCode());
        assertEquals(200, call("POST", "/api/v1/integrations/zabbix/items/sync", true).statusCode());
        var first = call("GET", PATH + "&limit=1", true);
        assertEquals(200, first.statusCode(), first.body());
        var artifact = java.nio.file.Path.of("build/test-results/history-page.json");
        java.nio.file.Files.createDirectories(artifact.getParent());
        java.nio.file.Files.writeString(artifact, first.body());
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
        var body = json.readTree(first.body());
        assertEquals("labeled-fixture", body.get("dataMode").asString());
        assertEquals("not-persisted", body.get("persistence").asString());
        assertEquals("tenant-demo", body.get("tenantId").asString());
        assertEquals("host.cpu.usage.user", body.get("metricKey").asString());
        assertEquals("1", body.get("unit").asString());
        assertEquals("user", body.get("dimensions").get("mode").asString());
        assertEquals("0.25", body.get("points").get(0).get("value").asString());
        assertEquals(100, body.get("points").get(0).get("ns").asInt());
        assertFalse(body.get("windowComplete").asBoolean());
        var next = call("GET", PATH + "&afterClock=" + START + "&afterNs=100", true);
        assertEquals(200, next.statusCode(), next.body());
        var page = json.readTree(next.body());
        assertEquals(2, page.get("points").size());
        assertEquals(200, page.get("points").get(0).get("ns").asInt());
        assertTrue(page.get("windowComplete").asBoolean());
        assertEquals(999_999_999, page.get("nextCursor").get("ns").asInt());
    }

    @Test
    void rejectsInvalidWindowsCursorsLimitsAndUnknownBindings() throws Exception {
        for (String suffix : new String[]{"&limit=0", "&limit=501", "&afterClock=" + START,
            "&afterClock=" + START + "&afterNs=1000000000", "&afterNs=0", "&afterClock=1&afterNs=0"}) {
            var response = call("GET", PATH + suffix, true);
            assertEquals(400, response.statusCode(), response.body());
            assertFalse(response.body().contains("nextCursor"));
        }
        assertEquals(400, call("GET", "/api/v1/integrations/zabbix/items/20001/history?from=0&till=3600", true).statusCode());
        long future = Instant.now().getEpochSecond() + 100;
        assertEquals(400, call("GET", "/api/v1/integrations/zabbix/items/20001/history?from=" + future + "&till=" + future, true).statusCode());
        assertEquals(404, call("GET", PATH.replace("20001", "99999"), true).statusCode());
    }

    private HttpResponse<String> call(String method, String path, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(java.time.Duration.ofSeconds(10));
        if (authenticated) request.header("Authorization", "Bearer history-test-token-not-for-real-use");
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
