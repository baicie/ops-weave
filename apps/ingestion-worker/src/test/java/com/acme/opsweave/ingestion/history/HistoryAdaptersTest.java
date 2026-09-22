package com.acme.opsweave.ingestion.history;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.api.HistoryIngestionPorts.Failure;
import com.acme.opsweave.integration.domain.HistoryStream;
import com.acme.opsweave.integration.domain.HistoryWindow;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HistoryAdaptersTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN = "test-history-reader-token-not-for-production";
    private static final HistoryStream STREAM = new HistoryStream(new TenantId("tenant-demo"), "zabbix-1", "20001", "test");
    private static final HistoryWindow WINDOW = new HistoryWindow(1789992000, 1789992010, null, 100);

    @Test
    void readsPublishedContractExampleAndKeepsIdentityOutOfRequest() throws Exception {
        try (Stub stub = new Stub()) {
            var slice = new PlatformHistoryReader(stub.origin(), TOKEN, "labeled-fixture").read(STREAM, WINDOW);
            assertEquals("0.25", slice.page().points().getFirst().value().toPlainString());
            assertEquals("user", slice.labels().get("dimension_mode"));
            assertEquals("tenant-demo", slice.labels().get("tenant_id"));
            assertEquals("Bearer " + TOKEN, stub.auth);
            assertFalse(stub.query.contains("tenant"));
        }
    }

    @Test
    void rejectsIdentityModeSchemaAndHttpFailuresWithoutFallback() throws Exception {
        try (Stub stub = new Stub()) {
            String original = stub.source;
            var reader = new PlatformHistoryReader(stub.origin(), TOKEN, "labeled-fixture");
            for (String bad : List.of(original.replace("tenant-demo", "other-tenant"), original.replace("labeled-fixture", "zabbix-jsonrpc"),
                original.replace("\"ns\": 100", "\"ns\": 1000000000"), original.replace("\"value\": \"0.25\"", "\"value\": 0.25"))) {
                stub.source = bad;
                assertEquals(Failure.Code.INVALID_PAGE, assertThrows(Failure.class, () -> reader.read(STREAM, WINDOW)).code());
            }
            stub.status = 503;
            assertEquals(Failure.Code.SOURCE_FAILED, assertThrows(Failure.class, () -> reader.read(STREAM, WINDOW)).code());
            stub.status = 200;
            stub.source = "x".repeat(2 * 1024 * 1024 + 1);
            assertEquals(Failure.Code.SOURCE_FAILED, assertThrows(Failure.class, () -> reader.read(STREAM, WINDOW)).code());
        }
        assertThrows(IllegalArgumentException.class, () -> new PlatformHistoryReader(URI.create("http://example.com"), TOKEN, "zabbix-jsonrpc"));
    }

    @Test
    void writesBoundedBatchAndVerifiesVisibilityThenSkipsAnExactReplay() throws Exception {
        try (Stub stub = new Stub()) {
            var batch = batch();
            stub.expected = batch;
            var writer = new VictoriaMetricsWriter(stub.origin());
            writer.write(batch);
            assertEquals(1, stub.posts);
            assertTrue(stub.postBody.contains("opsweave_metric,tenant_id=tenant-demo value=0.25 100000\n"));
            assertEquals("precision=ms", stub.postQuery);
            assertEquals("0", stub.streamMode);
            assertTrue(stub.exportQuery.contains("start=100&end=101"));
            assertNull(stub.writeAuth);
            writer.write(batch);
            assertEquals(1, stub.posts);
        }
    }

    @Test
    void refusesBadDedupConfigAndStoredValueConflictBeforePosting() throws Exception {
        try (Stub stub = new Stub()) {
            var writer = new VictoriaMetricsWriter(stub.origin());
            stub.flags = "-dedup.minScrapeInterval=\"0s\"";
            assertEquals(Failure.Code.CONFIGURATION_INVALID, assertThrows(Failure.class, () -> writer.write(batch())).code());
            assertEquals(0, stub.posts);
            stub.flags = "-dedup.minScrapeInterval=\"1ms\"";
            stub.expected = MetricWriteBatch.from(batch().labels(), List.of(new MetricPoint(Instant.ofEpochSecond(100), new BigDecimal("0.99"))));
            stub.stored = true;
            assertEquals(Failure.Code.STORED_VALUE_CONFLICT, assertThrows(Failure.class, () -> writer.write(batch())).code());
            assertEquals(0, stub.posts);
        }
    }

    @Test
    void doesNotTreatPostSuccessOrServerErrorAsVerifiedStorage() throws Exception {
        try (Stub stub = new Stub()) {
            stub.expected = batch();
            stub.acceptWithoutStorage = true;
            var writer = new VictoriaMetricsWriter(stub.origin(), 2, 1);
            assertEquals(Failure.Code.SINK_UNCONFIRMED, assertThrows(Failure.class, () -> writer.write(batch())).code());
            assertEquals(1, stub.posts);
            stub.writeStatus = 503;
            assertEquals(Failure.Code.SINK_FAILED, assertThrows(Failure.class, () -> writer.write(batch())).code());
            assertEquals(2, stub.posts);
        }
    }

    private static MetricWriteBatch batch() {
        return MetricWriteBatch.from(Map.of("tenant_id", "tenant-demo"), List.of(new MetricPoint(Instant.ofEpochSecond(100), new BigDecimal("0.25"))));
    }

    private static final class Stub implements AutoCloseable {
        final HttpServer server;
        volatile String source;
        volatile String flags = "-dedup.minScrapeInterval=\"1ms\"\n-influx.forceStreamMode=\"false\"";
        volatile String auth, query, postBody, postQuery, streamMode, writeAuth, exportQuery;
        volatile int status = 200, writeStatus = 204, posts;
        volatile boolean stored, acceptWithoutStorage;
        volatile MetricWriteBatch expected;

        Stub() throws Exception {
            try (var input = getClass().getClassLoader().getResourceAsStream("contracts/metric-history-page.json")) {
                source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath();
                    String body;
                    int code = 200;
                    if (path.equals("/flags")) body = flags;
                    else if (path.equals("/write")) {
                        posts++; postBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        postQuery = exchange.getRequestURI().getQuery(); streamMode = exchange.getRequestHeaders().getFirst("Stream-Mode");
                        writeAuth = exchange.getRequestHeaders().getFirst("Authorization");
                        if (!acceptWithoutStorage && writeStatus == 204) stored = true;
                        body = ""; code = writeStatus;
                    } else if (path.equals("/api/v1/export")) {
                        exportQuery = exchange.getRequestURI().getQuery();
                        Map<String, String> labels = expected == null ? new HashMap<>() : new HashMap<>(expected.labels());
                        labels.put("__name__", "opsweave_metric_value");
                        body = stored ? JSON.writeValueAsString(Map.of("metric", labels, "timestamps", expected.samples().stream().map(MetricWriteBatch.Sample::timestampMillis).toList(),
                            "values", expected.samples().stream().map(MetricWriteBatch.Sample::value).toList())) : "";
                    } else {
                        auth = exchange.getRequestHeaders().getFirst("Authorization"); query = exchange.getRequestURI().getQuery(); body = source; code = status;
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(code, code == 204 ? -1 : bytes.length == 0 ? 0 : bytes.length);
                    if (code != 204) exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start();
        }
        URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        @Override public void close() { server.stop(0); }
    }
}
