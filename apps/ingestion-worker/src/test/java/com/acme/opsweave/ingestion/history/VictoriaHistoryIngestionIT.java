package com.acme.opsweave.ingestion.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.acme.opsweave.integration.api.HistoryIngestionPorts.*;
import com.acme.opsweave.integration.application.IngestMetricHistoryUseCase;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** Real PostgreSQL + VictoriaMetrics. Only the platform History source is a labeled protocol fixture. */
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_VM_URL", matches = ".+")
class VictoriaHistoryIngestionIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TOKEN = "fixture-platform-token-not-real-credentials";

    @Test
    void overlapCollectsLatePointsAndRestartReplaysWithoutExtraStoredSamples() throws Exception {
        assumeTrue(System.getenv("OPSWEAVE_TEST_JDBC_URL") != null);
        try (var dataSource = PostgresHistoryCheckpointIT.dataSource(); var source = new FixtureSource()) {
            var store = new PostgresHistoryCheckpointStore(dataSource); store.initialize();
            var reader = new PlatformHistoryReader(source.origin(), TOKEN, "labeled-fixture");
            var writer = new VictoriaMetricsWriter(URI.create(System.getenv("OPSWEAVE_TEST_VM_URL")));
            var clock = Clock.fixed(Instant.ofEpochSecond(source.from + 130), ZoneOffset.UTC);
            var policy = new HistoryPollPolicy(60, 120, 10, 4);
            var useCase = new IngestMetricHistoryUseCase(reader, writer, store, policy, clock);
            var first = useCase.poll(source.stream, source.from);
            assertEquals(2, first.confirmedPoints()); assertEquals(source.from + 59, first.till());
            source.points.add(point(source.from + 20, 0, "0.50"));
            var late = useCase.poll(source.stream, source.from);
            assertEquals(3, late.confirmedPoints()); assertEquals(source.from + 119, late.till());
            var reopened = new PostgresHistoryCheckpointStore(dataSource);
            var resumed = new IngestMetricHistoryUseCase(reader, writer, reopened, policy, clock).poll(source.stream, source.from);
            assertEquals(source.from + 119, resumed.till());
            assertEquals(3, rawSampleCount(source));
            assertEquals("Bearer " + TOKEN, source.authorization);
            assertFalse(source.lastQuery.contains("tenant"));

            source.points.set(0, point(source.from, 0, "0.99"));
            var conflict = assertThrows(Failure.class, () -> useCase.poll(source.stream, source.from));
            assertEquals(Failure.Code.STORED_VALUE_CONFLICT, conflict.code());
            assertEquals(3, rawSampleCount(source));
        }
    }

    @Test
    void interruptionAfterConfirmedWriteRollsBackCursorAndReplayReusesThePoints() throws Exception {
        assumeTrue(System.getenv("OPSWEAVE_TEST_JDBC_URL") != null);
        try (var dataSource = PostgresHistoryCheckpointIT.dataSource(); var source = new FixtureSource()) {
            var store = new PostgresHistoryCheckpointStore(dataSource); store.initialize();
            var reader = new PlatformHistoryReader(source.origin(), TOKEN, "labeled-fixture");
            var writer = new VictoriaMetricsWriter(URI.create(System.getenv("OPSWEAVE_TEST_VM_URL")));
            CheckpointStore interrupted = (stream, from, work) -> store.withLock(stream, from, before -> {
                work.apply(before);
                throw new Failure(Failure.Code.CHECKPOINT_FAILED);
            });
            var policy = new HistoryPollPolicy(60, 120, 10, 4);
            var clock = Clock.fixed(Instant.ofEpochSecond(source.from + 180), ZoneOffset.UTC);
            var failed = new IngestMetricHistoryUseCase(reader, writer, interrupted, policy, clock);
            assertEquals(Failure.Code.CHECKPOINT_FAILED, assertThrows(Failure.class, () -> failed.poll(source.stream, source.from)).code());
            store.withLock(source.stream, source.from, before -> {
                assertEquals(source.from - 1, before.completedThrough()); return new Update(before, Result.idle(before.completedThrough()));
            });
            assertEquals(2, rawSampleCount(source));
            new IngestMetricHistoryUseCase(reader, writer, store, policy, clock).poll(source.stream, source.from);
            assertEquals(2, rawSampleCount(source));
        }
    }

    @Test
    void actualStorageCannotAcknowledgeAnUnqueryableExpiredPointAsConfirmed() {
        var labels = Map.of("tenant_id", "tenant-demo", "source_instance_id", "expired-" + UUID.randomUUID());
        var batch = MetricWriteBatch.from(labels, List.of(point(1, 0, "0.25")));
        var writer = new VictoriaMetricsWriter(URI.create(System.getenv("OPSWEAVE_TEST_VM_URL")), 1, 0);
        assertEquals(Failure.Code.SINK_UNCONFIRMED, assertThrows(Failure.class, () -> writer.write(batch)).code());
    }

    private static int rawSampleCount(FixtureSource source) {
        String selector = "opsweave_metric_value{source_instance_id=\"" + source.stream.sourceInstanceId() + "\"}";
        String path = "/api/v1/export?match%5B%5D=" + URLEncoder.encode(selector, StandardCharsets.UTF_8)
            + "&start=" + source.from + "&end=" + (source.from + 180) + "&reduce_mem_usage=1";
        var response = new LoopbackHttp(URI.create(System.getenv("OPSWEAVE_TEST_VM_URL"))).request("GET", path, null, Map.of());
        assertEquals(200, response.statusCode());
        int count = 0;
        for (String line : response.body().split("\n")) if (!line.isBlank()) count += JSON.readTree(line).get("timestamps").size();
        return count;
    }

    private static MetricPoint point(long clock, int ns, String value) { return new MetricPoint(Instant.ofEpochSecond(clock, ns), new BigDecimal(value)); }

    private static final class FixtureSource implements AutoCloseable {
        final HttpServer server;
        final long from = Instant.now().getEpochSecond() - 300;
        final String entity = UUID.randomUUID().toString();
        final HistoryStream stream = new HistoryStream(new TenantId("tenant-demo"), "zabbix-it-" + UUID.randomUUID().toString().substring(0, 8), "20001", "test");
        final List<MetricPoint> points = new CopyOnWriteArrayList<>(List.of(point(from, 0, "0.25"), point(from + 1, 1000000, "0.30")));
        volatile String authorization, lastQuery;

        FixtureSource() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    lastQuery = exchange.getRequestURI().getQuery();
                    Map<String, String> query = new java.util.HashMap<>();
                    for (String pair : lastQuery.split("&")) { String[] parts = pair.split("="); query.put(parts[0], parts[1]); }
                    var after = query.containsKey("afterClock") ? new HistoryCursor(Long.parseLong(query.get("afterClock")), Integer.parseInt(query.get("afterNs"))) : null;
                    var window = new HistoryWindow(Long.parseLong(query.get("from")), Long.parseLong(query.get("till")), after, Integer.parseInt(query.get("limit")));
                    var rows = points.stream().filter(point -> point.timestamp().getEpochSecond() >= window.fetchFrom() && point.timestamp().getEpochSecond() <= window.till())
                        .sorted(Comparator.comparing(MetricPoint::timestamp)).toList();
                    var page = HistoryPage.select(window, rows);
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("dataMode", "labeled-fixture"); body.put("persistence", "not-persisted"); body.put("tenantId", stream.tenantId().value());
                    body.put("sourceInstanceId", stream.sourceInstanceId()); body.put("externalItemId", stream.itemId()); body.put("entityId", entity);
                    body.put("metricKey", "host.cpu.usage.user"); body.put("unit", "1"); body.put("dimensions", Map.of("mode", "user"));
                    body.put("mappingRevision", 1); body.put("definitionVersion", 1); body.put("bindingVersion", 1);
                    body.put("from", window.from()); body.put("till", window.till()); body.put("nextCursor", page.nextCursor()); body.put("windowComplete", page.windowComplete());
                    body.put("points", page.points().stream().map(point -> Map.of("clock", point.timestamp().getEpochSecond(), "ns", point.timestamp().getNano(), "value", point.value().toPlainString())).toList());
                    byte[] bytes = JSON.writeValueAsBytes(body);
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start();
        }
        URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        @Override public void close() { server.stop(0); }
    }
}
