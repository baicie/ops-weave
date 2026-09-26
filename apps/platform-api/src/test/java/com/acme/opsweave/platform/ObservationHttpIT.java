package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=observation-test-only-token-not-for-use", "opsweave.auth.dev.subject=observation-user",
    "opsweave.auth.dev.tenant=tenant-observation", "opsweave.auth.dev.permissions=entity.read,source.sync",
    "opsweave.zabbix.mode=fixture", "opsweave.zabbix.source-instance-id=zabbix-1", "opsweave.inventory.store=memory"})
class ObservationHttpIT {
    @org.springframework.beans.factory.annotation.Autowired com.acme.opsweave.platform.persistence.InventoryWiring wiring;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void oversizedHistoryFailsExplicitlyWithoutAnEmptySuccess() throws Exception {
        var tenant = new com.acme.opsweave.sharedkernel.TenantId("tenant-observation");
        var key = new com.acme.opsweave.inventory.domain.ExternalObjectKey(tenant, "zabbix-1", "host", "oversized", "1");
        var id = com.acme.opsweave.inventory.domain.EntityIds.fromExternal(key); var now = Instant.now().minusSeconds(1);
        wiring.writer().upsert(new com.acme.opsweave.inventory.domain.Entity(id, tenant, "host", "oversized history", com.acme.opsweave.inventory.domain.Lifecycle.ACTIVE, 1, now, java.util.Map.of()),
            new com.acme.opsweave.inventory.domain.Observation("oversized", key, id, now, now, java.util.Map.of("large", "x".repeat(20000)), "raw-oversized", 1), new com.acme.opsweave.inventory.domain.ExternalLink(id, key));
        var response = call("GET", "/api/v1/entities/" + id.value() + "/observations?from=" + now.minusSeconds(60).getEpochSecond() + "&till=" + Instant.now().getEpochSecond(), true);
        assertEquals(503, response.statusCode()); assertEquals("INVENTORY_READ_UNAVAILABLE", json.readTree(response.body()).get("error").asString());
    }
    @Test void importsAndReadsBoundedAuthorizedHistoryWithOriginalKnowledgeCutoff() throws Exception {
        assertEquals(200, call("POST", "/api/v1/integrations/zabbix/hosts/sync", true).statusCode());
        var entity = json.readTree(call("GET", "/api/v1/entities/page?q=Zabbix", true).body()).get("items").get(0);
        String id = entity.get("id").asString(); long now = Instant.now().getEpochSecond();
        String path = "/api/v1/entities/" + id + "/observations?from=" + (now - 30 * 86400) + "&till=" + now + "&limit=1";
        var response = call("GET", path, true); assertEquals(200, response.statusCode());
        var first = json.readTree(response.body()); assertEquals(1, first.get("items").size());
        var record = first.get("items").get(0); assertEquals(id, record.get("entityId").asString());
        assertEquals("labeled-fixture", record.get("fields").get("dataMode").asString()); assertEquals("nanoseconds", record.get("timePrecision").asString());
        assertEquals(entity.get("name").asString(), record.get("fields").get("entityName").asString()); assertEquals(0, record.get("gaps").size());
        String cutoff = first.get("query").get("asOf").asString();
        assertEquals(200, call("POST", "/api/v1/integrations/zabbix/hosts/sync", true).statusCode());
        var historical = json.readTree(call("GET", path + "&asOf=" + cutoff, true).body()); assertEquals(first, historical);
        assertEquals(0, json.readTree(call("GET", path + "&source=other", true).body()).get("items").size());
        var fresh = json.readTree(call("GET", path, true).body()); assertFalse(fresh.get("nextCursor").isNull());
        var next = call("GET", path + "&after=" + fresh.get("nextCursor").asString() + "&asOf=" + fresh.get("query").get("asOf").asString(), true);
        assertEquals(200, next.statusCode()); assertTrue(json.readTree(next.body()).get("nextCursor").isNull());
        Path output = Path.of("../../.tmp/observation-http"); Files.createDirectories(output);
        Files.writeString(output.resolve("observation-page.json"), response.body()); Files.writeString(output.resolve("observation.json"), record.toString());
        assertEquals(401, call("GET", path, false).statusCode());
        assertEquals(404, call("GET", path.replace(id, "00000000-0000-0000-0000-000000000000"), true).statusCode());
    }
    @Test void invalidOrUnboundedQueriesAreRejected() throws Exception {
        String path = "/api/v1/entities/00000000-0000-0000-0000-000000000000/observations?from=1790294400&till=1790295000";
        for (String suffix : new String[]{"&limit=0", "&limit=51", "&limit=no", "&after=../raw", "&asOf=yesterday", "&source=a%0Ab", "&source=a&source=b", "&unexpected=1", "&tenantId=other"}) {
            int status = call("GET", path + suffix, true).statusCode(); assertTrue(status == 400 || status == 403, suffix + ": " + status);
        }
    }
    private HttpResponse<String> call(String method, String path, boolean authorized) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (authorized) request.header("Authorization", "Bearer observation-test-only-token-not-for-use");
        if (method.equals("POST")) request.POST(HttpRequest.BodyPublishers.noBody()); else request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
