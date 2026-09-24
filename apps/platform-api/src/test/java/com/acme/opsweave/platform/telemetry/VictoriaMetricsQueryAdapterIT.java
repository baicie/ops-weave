package com.acme.opsweave.platform.telemetry;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.api.MetricQueryException;
import com.acme.opsweave.telemetry.domain.MetricSeriesQuery;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.Status;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_VM_URL", matches = ".+")
class VictoriaMetricsQueryAdapterIT {
    @Test
    void keepsSourcesSeparateAndDoesNotTreatEmptyOrDownAsTheSameResult() throws Exception {
        URI origin = URI.create(System.getenv("OPSWEAVE_TEST_VM_URL"));
        String metric = "host.cpu.usage.user." + UUID.randomUUID().toString().substring(0, 8);
        var entity = new EntityId(UUID.randomUUID());
        long second = System.currentTimeMillis() / 1000 - 30;
        String labels = "tenant_id=tenant-demo,entity_id=" + entity.value() + ",metric_key=" + metric
            + ",unit=1,mapping_revision=3,data_mode=labeled-fixture,dimension_mode=user";
        String body = "opsweave_metric," + labels + ",source_instance_id=zabbix-1,external_item_id=20001 value=0.31 " + (second * 1000) + "\n"
            + "opsweave_metric," + labels + ",source_instance_id=prom-1,external_item_id=cpu value=0.22 " + (second * 1000) + "\n";
        var write = HttpRequest.newBuilder(origin.resolve("/write?precision=ms"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var written = HttpClient.newHttpClient().send(write, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, written.statusCode());
        var adapter = new VictoriaMetricsQueryAdapter(origin);
        var query = new MetricSeriesQuery(new TenantId("tenant-demo"), entity, metric, second - 5, second + 5, 50, 30);
        var page = awaitSeries(adapter, query);
        assertEquals(2, page.series().size());
        assertEquals("prom-1", page.series().get(0).sourceInstanceId());
        assertEquals("zabbix-1", page.series().get(1).sourceInstanceId());
        assertEquals("user", page.series().get(0).dimensions().get("mode"));
        assertEquals(Status.Kind.AVAILABLE, page.status().kind());
        var missing = adapter.query(new MetricSeriesQuery(new TenantId("tenant-demo"), entity, metric + ".missing", second - 5, second + 5, 50, 30));
        assertEquals(Status.Kind.NO_DATA, missing.status().kind());
        assertTrue(missing.series().isEmpty());
        var down = new VictoriaMetricsQueryAdapter(URI.create("http://127.0.0.1:9"));
        var failed = assertThrows(MetricQueryException.class, () -> down.query(query));
        assertEquals(MetricQueryException.Code.SOURCE_UNAVAILABLE, failed.code());
    }

    private static com.acme.opsweave.telemetry.domain.MetricSeriesResult awaitSeries(VictoriaMetricsQueryAdapter adapter, MetricSeriesQuery query) throws InterruptedException {
        com.acme.opsweave.telemetry.domain.MetricSeriesResult page = adapter.query(query);
        for (int attempt = 0; attempt < 11 && page.series().isEmpty(); attempt++) {
            Thread.sleep(2000);
            page = adapter.query(query);
        }
        return page;
    }
}
