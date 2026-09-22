import com.acme.opsweave.integration.domain.HistoryCheckpoint;
import com.acme.opsweave.integration.domain.HistoryPollPolicy;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class HistoryIngestionPolicySmoke {
    private static int checks;
    public static void main(String[] args) {
        var policy = new HistoryPollPolicy(60, 120, 10, 8);
        var checkpoint = HistoryCheckpoint.initial(100);
        var first = policy.next(checkpoint, 1000).orElseThrow();
        check(first.from() == 100 && first.till() == 159);
        var batch = MetricWriteBatch.from(Map.of("tenant_id", "tenant-demo"), List.of(point(100, 1, "0.25"), point(100, 999999, "0.250")));
        check(batch.samples().size() == 1 && batch.collapsedPoints() == 1);
        check(batch.samples().getFirst().timestampMillis() == 100000);
        var advanced = checkpoint.accepted(500, batch.seriesHash());
        var next = policy.next(advanced, 1000).orElseThrow();
        check(next.from() == 381 && next.till() == 560);
        check(policy.next(advanced, 510).isEmpty());
        var overlapping = policy.next(advanced, 511).orElseThrow();
        check(overlapping.from() == 381 && overlapping.till() == 500);
        reject(() -> MetricWriteBatch.from(Map.of("tenant_id", "tenant-demo"), List.of(point(100, 1, "0.25"), point(100, 2, "0.30"))));
        reject(() -> MetricWriteBatch.from(Map.of("tenant_id", "tenant-demo"), List.of(point(100, 0, "18446744073709551615"))));
        reject(() -> MetricWriteBatch.from(Map.of("tenant_id", "tenant-demo\nother=tenant"), List.of()));
        reject(() -> new HistoryPollPolicy(1801, 1800, 10, 8));
        reject(() -> new HistoryPollPolicy(60, 120, 10, 9));
        reject(() -> advanced.accepted(499, batch.seriesHash()));
        reject(() -> advanced.accepted(501, "f".repeat(64)));
        check(!batch.seriesHash().equals(MetricWriteBatch.from(Map.of("tenant_id", "other"), List.of()).seriesHash()));
        System.out.println("HistoryIngestionPolicySmoke: " + checks + " checks passed");
    }
    private static MetricPoint point(long clock, int ns, String value) { return new MetricPoint(Instant.ofEpochSecond(clock, ns), new BigDecimal(value)); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Check " + checks); checks++; }
    private static void reject(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
}
