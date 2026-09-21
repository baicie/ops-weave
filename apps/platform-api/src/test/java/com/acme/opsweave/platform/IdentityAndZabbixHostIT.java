package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    "opsweave.auth.dev.permissions=entity.read,source.sync",
    "opsweave.zabbix.mode=fixture",
    "opsweave.zabbix.source-instance-id=zabbix-1"
})
class IdentityAndZabbixHostIT {
    private static final String TOKEN = "test-dev-token-please-do-not-use-elsewhere";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;

    @Test
    void healthRemainsPublic() throws Exception {
        assertEquals(200, call("GET", "/actuator/health", null).statusCode());
    }

    @Test
    void apiWithoutTokenIsUnauthorized() throws Exception {
        assertEquals(401, call("GET", "/api/v1/entities", null).statusCode());
    }

    @Test
    void clientTenantOverrideIsRejected() throws Exception {
        assertEquals(400, call("GET", "/api/v1/entities?tenantId=attacker", TOKEN).statusCode());
    }

    @Test
    void wrongTokenIsUnauthorized() throws Exception {
        assertEquals(401, call("GET", "/api/v1/entities", "wrong-dev-token-please-do-not-use-elsewhere").statusCode());
    }

    @Test
    void fixtureHostSyncWritesEntityVisibleToTrustedPrincipal() throws Exception {
        HttpResponse<String> synced = call("POST", "/api/v1/integrations/zabbix/hosts/sync", TOKEN);
        assertEquals(200, synced.statusCode(), synced.body());
        JsonNode syncBody = mapper.readTree(synced.body());
        assertEquals("labeled-fixture", syncBody.get("dataMode").asString());
        assertEquals(2, syncBody.get("accepted").asInt());
        assertTrue(syncBody.get("snapshotComplete").asBoolean());

        HttpResponse<String> listed = call("GET", "/api/v1/entities", TOKEN);
        assertEquals(200, listed.statusCode(), listed.body());
        JsonNode items = mapper.readTree(listed.body()).get("items");
        assertEquals(2, items.size());
        String entityId = items.get(0).get("id").asString();

        HttpResponse<String> entity = call("GET", "/api/v1/entities/" + entityId, TOKEN);
        assertEquals(200, entity.statusCode(), entity.body());
        JsonNode body = mapper.readTree(entity.body());
        assertEquals("host", body.get("entityType").asString());
        assertEquals("tenant-demo", body.get("tenantId").asString());
        JsonNode attributes = body.get("attributes");
        assertEquals("zabbix", attributes.get("source").asString());
        assertTrue(attributes.get("hostId") != null && !attributes.get("hostId").asString().isBlank());
        assertTrue(attributes.get("ip") != null);
        assertTrue(attributes.get("status") != null);
        assertTrue(attributes.get("lastSeen") != null);
        assertTrue(attributes.get("rawReference") != null);

        HttpResponse<String> again = call("POST", "/api/v1/integrations/zabbix/hosts/sync", TOKEN);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals(2, mapper.readTree(again.body()).get("accepted").asInt());
        assertEquals(2, mapper.readTree(call("GET", "/api/v1/entities", TOKEN).body()).get("items").size());
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
