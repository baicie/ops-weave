package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev",
    "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=test-dev-token-please-do-not-use-elsewhere",
    "opsweave.auth.dev.subject=user-demo",
    "opsweave.auth.dev.tenant=tenant-demo",
    "opsweave.auth.dev.permissions=metric.read,source.sync",
    "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=zabbix-1",
    "opsweave.zabbix.page-size=1",
    "opsweave.inventory.store=memory"
})
class ZabbixItemSyncIT {
    private static final String TOKEN = "test-dev-token-please-do-not-use-elsewhere";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;

    @Test
    void fixtureItemSyncPublishesCpuUserDefinition() throws Exception {
        assertEquals(401, call("GET", "/api/v1/metrics/definitions", null).statusCode());
        assertEquals(400, call("GET", "/api/v1/metrics/definitions?tenantId=attacker", TOKEN).statusCode());

        HttpResponse<String> synced = call("POST", "/api/v1/integrations/zabbix/items/sync", TOKEN);
        assertEquals(200, synced.statusCode(), synced.body());
        JsonNode syncBody = mapper.readTree(synced.body());
        assertEquals(1, syncBody.get("accepted").asInt());
        assertEquals(1, syncBody.get("rejected").asInt());
        assertEquals(2, syncBody.get("pages").asInt());
        assertTrue(syncBody.get("snapshotComplete").asBoolean());
        assertEquals("offset-scan-attempt", syncBody.get("scanConsistency").asString());
        assertEquals("labeled-fixture", syncBody.get("dataMode").asString());
        assertFalse(synced.body().contains("history"));

        HttpResponse<String> listed = call("GET", "/api/v1/metrics/definitions", TOKEN);
        assertEquals(200, listed.statusCode(), listed.body());
        JsonNode items = mapper.readTree(listed.body()).get("items");
        assertEquals(1, items.size());
        JsonNode metric = items.get(0);
        assertEquals("host.cpu.usage.user", metric.get("name").asString());
        assertEquals("host", metric.get("entityType").asString());
        assertEquals("1", metric.get("unit").asString());
        assertEquals("DOUBLE", metric.get("valueType").asString());
        assertEquals("GAUGE", metric.get("metricType").asString());
        assertEquals("user", metric.get("dimensions").get("mode").asString());
        assertEquals("source", metric.get("origin").asString());
        assertEquals("system.cpu.util[,user]", metric.get("externalMapping").get("itemKey").asString());
        assertEquals("10084", metric.get("externalMapping").get("hostExternalId").asString());
        assertEquals("multiply:0.01", metric.get("externalMapping").get("valueTransform").asString());
    }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
