package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.integration.domain.SyncFailureCode;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    InventoryWiring wiring;

    @Test
    void fixtureItemSyncPublishesCpuUserDefinition() throws Exception {
        assertEquals(401, call("GET", "/api/v1/metrics/definitions", null).statusCode());
        assertEquals(400, call("GET", "/api/v1/metrics/definitions?tenantId=attacker", TOKEN).statusCode());
        assertEquals(403, call("GET", "/api/v1/integrations/zabbix/items/20001/history?from=10&till=20", TOKEN).statusCode());

        HttpResponse<String> synced = call("POST", "/api/v1/integrations/zabbix/items/sync", TOKEN);
        assertEquals(200, synced.statusCode(), synced.body());
        JsonNode syncBody = mapper.readTree(synced.body());
        assertEquals(1, syncBody.get("accepted").asInt());
        assertEquals(1, syncBody.get("rejected").asInt());
        assertEquals(2, syncBody.get("pages").asInt());
        assertTrue(syncBody.get("snapshotComplete").asBoolean());
        assertEquals("itemid-watermark-snapshot", syncBody.get("scanConsistency").asString());
        assertEquals("labeled-fixture", syncBody.get("dataMode").asString());
        assertFalse(synced.body().contains("history"));

        HttpResponse<String> listed = call("GET", "/api/v1/metrics/definitions", TOKEN);
        assertEquals(200, listed.statusCode(), listed.body());
        JsonNode items = mapper.readTree(listed.body()).get("items");
        assertEquals(1, items.size());
        JsonNode metric = items.get(0);
        assertEquals("host.cpu.usage.user", metric.get("metricKey").asString());
        assertEquals("Host CPU usage (user)", metric.get("displayName").asString());
        assertEquals("1", metric.get("unit").asString());
        assertEquals("DOUBLE", metric.get("valueType").asString());
        assertEquals("GAUGE", metric.get("metricType").asString());
        assertEquals("mode", metric.get("dimensionSchema").get(0).asString());
        assertFalse(listed.body().contains("externalMapping"));
        assertFalse(listed.body().contains("20001"));

        HttpResponse<String> bound = call("GET", "/api/v1/metrics/bindings", TOKEN);
        assertEquals(200, bound.statusCode(), bound.body());
        JsonNode bindings = mapper.readTree(bound.body()).get("items");
        assertEquals(1, bindings.size());
        JsonNode binding = bindings.get(0);
        assertEquals("20001", binding.get("externalItemId").asString());
        assertEquals("10084", binding.get("hostExternalId").asString());
        assertEquals("host.cpu.usage.user", binding.get("metricKey").asString());
        assertEquals("user", binding.get("fixedDimensions").get("mode").asString());
        assertEquals("multiply:0.01", binding.get("valueTransform").asString());
        assertEquals("ACTIVE", binding.get("lifecycle").asString());
    }

    @Test
    void aForeignItemLeaseReturnsBusyWithoutWritingOrRetiring() throws Exception {
        var tenant = new TenantId("tenant-demo");
        var holder = wiring.writer().beginScan(new SourceScan.Scope(tenant, "zabbix-1", "item"), UUID.randomUUID());
        HttpResponse<String> blocked = call("POST", "/api/v1/integrations/zabbix/items/sync", TOKEN);
        assertEquals(503, blocked.statusCode(), blocked.body());
        JsonNode body = mapper.readTree(blocked.body());
        assertEquals("source_unavailable", body.get("error").asString());
        assertEquals("SOURCE_SCAN_BUSY", body.get("failureCode").asString());
        assertEquals(SyncFailureCode.SOURCE_SCAN_BUSY.safeSummary(), body.get("summary").asString());
        assertFalse(body.get("snapshotComplete").asBoolean());
        assertEquals(0, body.get("pages").asInt());
        assertEquals(0, body.get("accepted").asInt());
        assertEquals("offset-scan-attempt", body.get("scanConsistency").asString(),
            "a scan that never read a page makes no method claim");
        assertTrue(body.hasNonNull("syncRunId"));
        wiring.writer().renewScan(holder);
        wiring.writer().releaseScan(holder);
        assertEquals(200, call("POST", "/api/v1/integrations/zabbix/items/sync", TOKEN).statusCode());
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
