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

class VictoriaMetricsQueryContractTest {
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
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            var output = exchange.getResponseBody();
            byte[] chunk = new byte[64 * 1024];
            try {
                for (int i = 0; i < 64; i++) output.write(chunk);
                output.close();
            } catch (Exception closed) {
                stoppedEarly.set(true);
            }
        });
        server.start();
        try {
            var failed = assertThrows(MetricQueryException.class, () -> query(server, entity));
            assertEquals(MetricQueryException.Code.INVALID_RESPONSE, failed.code());
            assertTrue(stoppedEarly.get());
        } finally { server.stop(0); }
    }

    private static void query(HttpServer server, EntityId entity) {
        new VictoriaMetricsQueryAdapter(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .query(new MetricSeriesQuery(new TenantId("tenant-demo"), entity, "up", 1, 10, 10, 10));
    }
}
