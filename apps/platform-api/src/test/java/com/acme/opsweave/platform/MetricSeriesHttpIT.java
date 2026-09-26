package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP/auth/controller/adapter chain against an explicitly labeled storage protocol stub. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=metric-query-test-token-not-for-real-use", "opsweave.auth.dev.subject=user-demo",
    "opsweave.auth.dev.tenant=tenant-demo", "opsweave.auth.dev.permissions=entity.read,metric.read,source.sync",
    "opsweave.zabbix.mode=fixture", "opsweave.inventory.store=memory"
})
class MetricSeriesHttpIT {
    private static final String TOKEN = "metric-query-test-token-not-for-real-use";
    private static final String METRIC = "host.cpu.usage.user";
    private static final HttpServer STORAGE = storage();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile String export = "";
    private static volatile int storageStatus = 200;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    @LocalServerPort int port;
    private String entity;
    private long till;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("opsweave.metrics.victoria-url", () -> "http://127.0.0.1:" + STORAGE.getAddress().getPort());
    }

    @AfterAll static void closeStorage() { STORAGE.stop(0); }

    @BeforeEach
    void seedLabeledFixture() throws Exception {
        assertEquals(200, call("POST", "/api/v1/integrations/zabbix/hosts/sync", TOKEN).statusCode());
        assertEquals(200, call("POST", "/api/v1/integrations/zabbix/items/sync", TOKEN).statusCode());
        entity = json.readTree(call("GET", "/api/v1/entities", TOKEN).body()).get("items").get(0).get("id").asString();
        till = Instant.now().getEpochSecond() - 1;
        export = row("zabbix-1", till - 2, "0.40");
        storageStatus = 200;
        CALLS.set(0);
    }

    @Test
    void returnsAuthorizedProvenanceAndEnforcesGlobalPointBudget() throws Exception {
        export += row("zabbix-2", till - 1, "0.50");
        var response = call("GET", path(), TOKEN);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var body = json.readTree(response.body());
        assertEquals(2, body.get("series").size());
        assertEquals("labeled-fixture", body.get("series").get(0).get("dataMode").asString());
        assertEquals("AVAILABLE", body.get("status").get("kind").asString());
        assertEquals((till - 1) * 1000, body.get("status").get("lastPointAt").asLong());
        Path artifact = Path.of("build/test-results/metric-series-page.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, response.body());
        var bounded = json.readTree(call("GET", path() + "&maxPoints=1", TOKEN).body());
        assertEquals("PARTIAL", bounded.get("status").get("kind").asString());
        assertTrue(bounded.get("status").get("partial").asBoolean());
        assertEquals(1, bounded.get("series").size());
        assertEquals("zabbix-2", bounded.get("series").get(0).get("sourceInstanceId").asString());
    }

    @Test
    void rejectsIdentityOverridesAndInvalidQueriesBeforeStorage() throws Exception {
        assertEquals(401, call("GET", path(), null).statusCode());
        assertEquals(401, call("GET", path(), TOKEN + "wrong").statusCode());
        for (String suffix : new String[]{"&tenantId=other", "&permissions=admin", "&maxPoints=0", "&maxPoints=501", "&maxPoints=bad"}) {
            assertEquals(400, call("GET", path() + suffix, TOKEN).statusCode(), suffix);
        }
        assertEquals(400, call("GET", path().replace(entity, "invalid-id"), TOKEN).statusCode());
        assertEquals(400, call("GET", base() + "?from=0&till=3601", TOKEN).statusCode());
        assertEquals(400, call("GET", base() + "?from=" + (till + 100) + "&till=" + (till + 200), TOKEN).statusCode());
        assertEquals(0, CALLS.get());
    }

    @Test
    void missingObjectsDoNotReachStorage() throws Exception {
        assertEquals(404, call("GET", path().replace(entity, "11111111-1111-1111-1111-111111111111"), TOKEN).statusCode());
        assertEquals(404, call("GET", path().replace(METRIC, "missing.metric"), TOKEN).statusCode());
        assertEquals(0, CALLS.get());
    }

    @Test
    void distinguishesNoDataStaleAndUnavailable() throws Exception {
        export = "";
        var empty = json.readTree(call("GET", path(), TOKEN).body());
        assertEquals("NO_DATA", empty.get("status").get("kind").asString());
        assertTrue(empty.get("status").get("lastPointAt").isNull());
        export = row("zabbix-1", till - 400, "0.10");
        assertEquals("STALE", json.readTree(call("GET", path(), TOKEN).body()).get("status").get("kind").asString());
        storageStatus = 503;
        var failed = call("GET", path(), TOKEN);
        assertEquals(503, failed.statusCode());
        assertEquals("SOURCE_UNAVAILABLE", json.readTree(failed.body()).get("error").asString());
        assertFalse(failed.body().contains("series"));
    }

    @Test
    void rejectsCrossTenantAndIncorrectUnitResponses() throws Exception {
        for (String body : new String[]{row("zabbix-1", till - 1, "0.4").replace("tenant-demo", "other-tenant"),
            row("zabbix-1", till - 1, "0.4").replace("\"unit\":\"1\"", "\"unit\":\"percent\"")}) {
            export = body;
            var response = call("GET", path(), TOKEN);
            assertEquals(503, response.statusCode(), response.body());
            assertEquals("INVALID_RESPONSE", json.readTree(response.body()).get("error").asString());
            assertFalse(response.body().contains("series"));
        }
    }

    private String row(String source, long second, String value) {
        return """
            {"metric":{"__name__":"opsweave_metric_value","tenant_id":"tenant-demo","entity_id":"%s","metric_key":"%s","source_instance_id":"%s","unit":"1","mapping_revision":"1","data_mode":"labeled-fixture","external_item_id":"20001","dimension_mode":"user"},"timestamps":[%d],"values":[%s]}
            """.formatted(entity, METRIC, source, second * 1000, value);
    }

    private String base() { return "/api/v1/entities/" + entity + "/metrics/" + METRIC + "/series"; }
    private String path() { return base() + "?from=" + (till - 900) + "&till=" + till; }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer storage() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/export", exchange -> {
                CALLS.incrementAndGet();
                byte[] bytes = export.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(storageStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
    }
}
