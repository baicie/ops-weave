package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=entity-page-test-only-token-not-for-use", "opsweave.auth.dev.subject=entity-page-user",
    "opsweave.auth.dev.tenant=tenant-entity-page", "opsweave.auth.dev.permissions=entity.read,source.sync",
    "opsweave.zabbix.mode=fixture", "opsweave.zabbix.source-instance-id=zabbix-1", "opsweave.inventory.store=memory"
})
class EntityPageHttpIT {
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void browsesFiltersAndReadsProvenance() throws Exception {
        assertEquals(200, call("POST", "/api/v1/integrations/zabbix/hosts/sync", true).statusCode());
        JsonNode first = ok(call("GET", "/api/v1/entities/page?type=host&limit=1", true));
        assertEquals("memory", first.get("storage").asString()); assertEquals(1, first.get("items").size());
        String cursor = first.get("nextCursor").asString();
        assertEquals(cursor, first.get("items").get(0).get("id").asString());
        JsonNode second = ok(call("GET", "/api/v1/entities/page?type=host&limit=1&after=" + cursor, true));
        assertEquals(1, second.get("items").size()); assertTrue(second.get("nextCursor").isNull());
        assertTrue(cursor.compareTo(second.get("items").get(0).get("id").asString()) < 0);
        JsonNode detail = ok(call("GET", "/api/v1/entities/" + cursor, true));
        assertEquals("zabbix-1", detail.get("attributes").get("sourceInstanceId").asString());
        assertEquals("labeled-fixture", detail.get("attributes").get("dataMode").asString());
        assertTrue(detail.get("attributes").get("pipelineDigest").asString().startsWith("sha256:"));
        String ip = detail.get("attributes").get("ip").asString();
        assertEquals(1, ok(call("GET", "/api/v1/entities/page?q=" + ip, true)).get("items").size());
        assertEquals(0, ok(call("GET", "/api/v1/entities/page?q=%25", true)).get("items").size());
        assertEquals(0, ok(call("GET", "/api/v1/entities/page?lifecycle=INACTIVE", true)).get("items").size());
        Path artifacts = Path.of("../../.tmp/entity-http"); Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("entity-page.json"), first.toString());
        Files.writeString(artifacts.resolve("entity.json"), detail.toString());
    }
    @Test void rejectsMalformedQueriesAndRequiresAuthentication() throws Exception {
        assertEquals(401, call("GET", "/api/v1/entities/page", false).statusCode());
        for (String query : new String[]{"limit=0", "limit=101", "limit=abc", "after=1-1-1-1-1", "after=",
            "lifecycle=UNKNOWN", "type=..", "q=" + "x".repeat(101), "q=x%0Ay", "q=a&q=b", "custom=1", "tenantId=other"}) {
            int status = call("GET", "/api/v1/entities/page?" + query, true).statusCode();
            assertTrue(status == 400 || status == 403, query + ": " + status);
        }
    }
    private JsonNode ok(HttpResponse<String> response) { assertEquals(200, response.statusCode(), response.body()); return json.readTree(response.body()); }
    private HttpResponse<String> call(String method, String path, boolean authorized) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (authorized) builder.header("Authorization", "Bearer entity-page-test-only-token-not-for-use");
        if (method.equals("POST")) builder.POST(HttpRequest.BodyPublishers.noBody()); else builder.GET();
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
