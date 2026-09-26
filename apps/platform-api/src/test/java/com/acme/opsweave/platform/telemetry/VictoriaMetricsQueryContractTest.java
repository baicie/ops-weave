package com.acme.opsweave.platform.telemetry;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.api.MetricQueryException;
import com.acme.opsweave.telemetry.domain.MetricSeriesQuery;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult;

class VictoriaMetricsQueryContractTest {
    @Test
    void invalidSampleShapesAndNegativeTimesReturnTypedErrors() throws Exception {
        var entity = new EntityId(UUID.randomUUID());
        var body = new AtomicReference<>("");
        var server = stub(body);
        try {
            for (String samples : new String[]{"\"timestamps\":[1000],\"values\":{\"0\":1}",
                "\"timestamps\":[-1],\"values\":[1]", "\"timestamps\":[1000],\"values\":[\"NaN\"]"}) {
                body.set(row(entity, samples));
                var failed = assertThrows(MetricQueryException.class, () -> query(server, entity));
                assertEquals(MetricQueryException.Code.INVALID_RESPONSE, failed.code());
            }
        } finally { server.stop(0); }
    }

    @Test
    void exhaustedScanWithNoInWindowPointsIsPartialInsteadOfNoData() throws Exception {
        var entity = new EntityId(UUID.randomUUID());
        var body = new AtomicReference<>(row(entity, "\"timestamps\":[" + "0,".repeat(8000) + "0],\"values\":[" + "1,".repeat(8000) + "1]"));
        var server = stub(body);
        try {
            var result = query(server, entity);
            assertEquals(MetricSeriesResult.Status.Kind.PARTIAL, result.status().kind());
            assertTrue(result.status().partial());
            assertFalse(result.status().fresh());
            assertTrue(result.series().isEmpty());
        } finally { server.stop(0); }
    }

    @Test
    void missingIdentityLabelIsAnInvalidResponse() throws Exception {
        var entity = new EntityId(UUID.randomUUID());
        String body = """
            {"metric":{"__name__":"opsweave_metric_value","tenant_id":"tenant-demo","entity_id":"%s","metric_key":"up","unit":"1","mapping_revision":"1","data_mode":"labeled-fixture","external_item_id":"20001"},"timestamps":[1000],"values":[1]}
            """.formatted(entity.value());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            var failed = assertThrows(MetricQueryException.class, () -> query(server, entity));
            assertEquals(MetricQueryException.Code.INVALID_RESPONSE, failed.code());
        } finally { server.stop(0); }
    }

    @Test
    void oversizedExportIsRejectedWhileTheBodyIsStillArriving() throws Exception {
        var entity = new EntityId(UUID.randomUUID());
        var stoppedEarly = new AtomicBoolean();
        var finished = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            var output = exchange.getResponseBody();
            byte[] chunk = new byte[64 * 1024];
            try {
                // Exceed socket buffers as well as the 2MB client limit.
                for (int i = 0; i < 1024; i++) output.write(chunk);
                output.close();
            } catch (Exception closed) {
                stoppedEarly.set(true);
            } finally { finished.countDown(); exchange.close(); }
        });
        server.start();
        try {
            var failed = assertThrows(MetricQueryException.class, () -> query(server, entity));
            assertEquals(MetricQueryException.Code.INVALID_RESPONSE, failed.code());
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(stoppedEarly.get());
        } finally { server.stop(0); }
    }

    private static MetricSeriesResult query(HttpServer server, EntityId entity) {
        return new VictoriaMetricsQueryAdapter(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .query(new MetricSeriesQuery(new TenantId("tenant-demo"), entity, "up", 1, 10, 10, 10));
    }

    private static String row(EntityId entity, String samples) {
        return """
            {"metric":{"__name__":"opsweave_metric_value","tenant_id":"tenant-demo","entity_id":"%s","metric_key":"up","source_instance_id":"zabbix-1","unit":"1","mapping_revision":"1","data_mode":"labeled-fixture","external_item_id":"20001"},%s}
            """.formatted(entity.value(), samples);
    }

    private static HttpServer stub(AtomicReference<String> body) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return server;
    }
}
