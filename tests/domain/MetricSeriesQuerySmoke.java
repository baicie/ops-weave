import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricSeriesQuery;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.MetricSeries;
import com.acme.opsweave.telemetry.domain.MetricSeriesResult.Sample;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricQuery;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MetricSeriesQuerySmoke {
    public static void main(String[] args) {
        var entity = new EntityId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        var query = new MetricSeriesQuery(new TenantId("tenant-demo"), entity, "host.cpu.usage.user", 1_000, 1_060, 2, 10);
        var zabbix = series("zabbix-1", "20001", 1_000_000L, new BigDecimal("0.31"), 1_060_000L, new BigDecimal("0.42"));
        var prometheus = series("prom-1", "cpu", 1_030_000L, new BigDecimal("0.20"), 1_050_000L, new BigDecimal("0.25"));
        var page = new InMemoryMetricQuery(List.of(zabbix, prometheus)).query(query);
        if (page.series().size() != 2 || page.status().kind() != MetricSeriesResult.Status.Kind.PARTIAL || !page.status().partial()) {
            throw new AssertionError("Sources stay separate and the point cap is partial");
        }
        if (!page.series().get(1).sourceInstanceId().equals("zabbix-1") || page.series().get(1).points().size() != 1) {
            throw new AssertionError("Newest points are kept per series without merging sources");
        }
        var stale = MetricSeriesResult.compose(query, List.of(series("zabbix-1", "20001", 1_000_000L, BigDecimal.ONE, 1_020_000L, new BigDecimal("0.2"))), false);
        if (stale.status().kind() != MetricSeriesResult.Status.Kind.STALE || stale.status().fresh() || stale.series().size() != 1) {
            throw new AssertionError("Old points are stale rather than empty");
        }
        var none = MetricSeriesResult.compose(query, List.of(), false);
        if (none.status().kind() != MetricSeriesResult.Status.Kind.NO_DATA || none.status().lastPointAtMillis() != null || !none.series().isEmpty()) {
            throw new AssertionError("Empty storage is no data");
        }
        var fresh = MetricSeriesResult.compose(query, List.of(series("zabbix-1", "20001", 1_050_000L, new BigDecimal("0.4"), 1_060_000L, new BigDecimal("0.5"))), false);
        if (fresh.status().kind() != MetricSeriesResult.Status.Kind.AVAILABLE || !fresh.status().fresh()) {
            throw new AssertionError("A point inside the freshness window is available");
        }
        reject(() -> new MetricSeriesQuery(new TenantId("tenant-demo"), entity, "up", 1_000, 5_000, 10, 10));
        reject(() -> new MetricSeriesQuery(new TenantId("tenant-demo"), entity, "up{label=\"x\"}", 1_000, 1_010, 10, 10));
        System.out.println("MetricSeriesQuerySmoke: 6 checks passed");
    }

    private static MetricSeries series(String source, String item, long firstAt, BigDecimal first, long secondAt, BigDecimal second) {
        return new MetricSeries(source, "labeled-fixture", item, 1, "1", Map.of("mode", "user"),
            List.of(new Sample(firstAt, first), new Sample(secondAt, second)));
    }

    private static void reject(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid query was accepted"); }
        catch (IllegalArgumentException expected) { /* rejected */ }
    }
}
