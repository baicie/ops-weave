package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricDefinition;
import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
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
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** A real HTTP counter query: raw points stay, rates are derived, and a reset is marked, not negative. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "opsweave.auth.mode=dev", "opsweave.auth.bind-loopback-only=true",
    "opsweave.auth.dev.token=counter-rate-test-token-not-for-real-use", "opsweave.auth.dev.subject=user-demo",
    "opsweave.auth.dev.tenant=tenant-demo", "opsweave.auth.dev.permissions=entity.read,metric.read,source.sync",
    "opsweave.zabbix.mode=fixture", "opsweave.inventory.store=memory"
})
class MetricCounterRateHttpIT {
    private static final String TOKEN = "counter-rate-test-token-not-for-real-use";
    private static final String GAUGE = "host.cpu.usage.user";
    private static final String COUNTER = "net.if.in.bytes";
    private static final HttpServer STORAGE = storage();
    private static volatile String export = "";
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired InventoryWiring wiring;
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
        wiring.metrics().upsert(new MetricDefinition(
            new TenantId("tenant-demo"), COUNTER, "Interface inbound bytes", "1",
            MetricValueType.DOUBLE, MetricType.SUM, List.of("iface"), 1
        ));
        entity = json.readTree(call("GET", "/api/v1/entities", TOKEN).body()).get("items").get(0).get("id").asString();
        till = Instant.now().getEpochSecond() - 1;
    }

    @Test
    void derivesRatesForCountersAndMarksTheResetWithoutTouchingRawPoints() throws Exception {
        // 100 -> 150 -> 20 (reset) -> 40 over ten-second intervals.
        export = String.join("", List.of(
            row(COUNTER, till - 40, "100"), row(COUNTER, till - 30, "150"),
            row(COUNTER, till - 20, "20"), row(COUNTER, till - 10, "40")
        ));
        var response = call("GET", path(COUNTER), TOKEN);
        assertEquals(200, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        assertEquals("counter-rate", body.get("derivation").get("kind").asString());
        assertEquals("reset-counts-from-zero", body.get("derivation").get("resetPolicy").asString());
        var row = body.get("series").get(0);
        assertEquals(4, row.get("points").size(), "raw points are preserved");
        var rates = row.get("counterRates");
        assertEquals(3, rates.size(), "one rate per interval");
        assertEquals("5.000000", rates.get(0).get("rate").asString());
        assertFalse(rates.get(0).get("counterReset").asBoolean());
        assertEquals("2.000000", rates.get(1).get("rate").asString(), "a reset counts from zero");
        assertTrue(rates.get(1).get("counterReset").asBoolean(), "the reset interval is marked");
        assertEquals("2.000000", rates.get(2).get("rate").asString());
        assertFalse(rates.get(2).get("counterReset").asBoolean());
        for (var rate : rates) {
            assertFalse(rate.get("rate").asString().startsWith("-"), "a derived rate is never negative");
        }
        Path artifact = Path.of("../../.tmp/metric-counter-http/metric-series-counter-page.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, response.body());
    }

    @Test
    void gaugesStayRawWithoutADerivation() throws Exception {
        export = row(GAUGE, till - 10, "0.40");
        var response = call("GET", path(GAUGE), TOKEN);
        assertEquals(200, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        assertTrue(body.get("derivation").isNull(), "a gauge page states no derivation");
        assertTrue(body.get("series").get(0).get("counterRates").isEmpty(), "a gauge page has no derived rates");
        Path artifact = Path.of("../../.tmp/metric-counter-http/metric-series-raw-page.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, response.body());
    }

    private String row(String metric, long second, String value) {
        return """
            {"metric":{"__name__":"opsweave_metric_value","tenant_id":"tenant-demo","entity_id":"%s","metric_key":"%s","source_instance_id":"zabbix-1","unit":"1","mapping_revision":"1","data_mode":"labeled-fixture","external_item_id":"20001","dimension_mode":"user"},"timestamps":[%d],"values":[%s]}
            """.formatted(entity, metric, second * 1000, value);
    }

    private String path(String metric) {
        return "/api/v1/entities/" + entity + "/metrics/" + metric + "/series?from=" + (till - 900) + "&till=" + till;
    }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer storage() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/export", exchange -> {
                byte[] bytes = export.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
    }
}
