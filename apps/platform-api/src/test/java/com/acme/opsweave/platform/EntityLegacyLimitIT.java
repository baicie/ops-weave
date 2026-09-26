package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true", "opsweave.auth.dev.token=entity-limit-test-token-not-for-other-use",
    "opsweave.auth.dev.subject=limit-user", "opsweave.auth.dev.tenant=entity-limit-tenant", "opsweave.auth.dev.permissions=entity.read",
    "opsweave.zabbix.mode=closed", "opsweave.inventory.store=memory"
})
class EntityLegacyLimitIT {
    @LocalServerPort int port;
    @Autowired InventoryWiring wiring;
    @Test void boundedCompatibilityReadDoesNotSilentlyTruncate() throws Exception {
        var tenant = new TenantId("entity-limit-tenant"); var now = Instant.now(); var json = JsonMapper.builder().build();
        for (int n = 1; n <= 101; n++) {
            var id = new EntityId(UUID.randomUUID()); var key = new ExternalObjectKey(tenant, "fixture-limit", "host", Integer.toString(n), "1");
            wiring.writer().upsert(new Entity(id, tenant, "host", "Fixture " + n, Lifecycle.ACTIVE, 1, now, Map.of()),
                new Observation("limit-" + n, key, id, now, now, Map.of(), "fixture-raw-" + n, 1), new ExternalLink(id, key));
            if (n == 100) {
                var response = call("/api/v1/entities"); assertEquals(200, response.statusCode());
                assertEquals(100, json.readTree(response.body()).get("items").size());
            }
        }
        var response = call("/api/v1/entities"); assertEquals(422, response.statusCode());
        assertEquals("PAGED_READ_REQUIRED", json.readTree(response.body()).get("error").asString());
        assertFalse(json.readTree(response.body()).has("items"));
        var paged = call("/api/v1/entities/page?limit=100"); assertEquals(200, paged.statusCode());
        assertEquals(100, json.readTree(paged.body()).get("items").size()); assertFalse(json.readTree(paged.body()).get("nextCursor").isNull());
        assertEquals(400, call("/api/v1/entities?limit=1000").statusCode());
    }
    private HttpResponse<String> call(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer entity-limit-test-token-not-for-other-use").GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
