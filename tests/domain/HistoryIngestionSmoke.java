import com.acme.opsweave.integration.api.HistoryIngestionPorts.*;
import com.acme.opsweave.integration.application.IngestMetricHistoryUseCase;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class HistoryIngestionSmoke {
    private static final HistoryStream STREAM = new HistoryStream(new TenantId("tenant-demo"), "zabbix-1", "20001", "test");
    private static final Map<String, String> LABELS = Map.of("tenant_id", "tenant-demo", "source_instance_id", "zabbix-1", "external_item_id", "20001");
    private static int checks;
    public static void main(String[] args) {
        Store store = new Store();
        AtomicInteger writes = new AtomicInteger();
        TestSource source = (stream, window) -> slice(LABELS, window, List.of(point(100, "0.25")));
        var useCase = useCase(source, batch -> { check(store.checkpoint.completedThrough() == 99); writes.incrementAndGet(); }, store);
        var accepted = useCase.poll(STREAM, 100);
        check(accepted.confirmedPoints() == 1 && store.checkpoint.completedThrough() == 159 && writes.get() == 1);
        TestSink changedDestination = new TestSink() {
            public void write(com.acme.opsweave.telemetry.domain.MetricWriteBatch batch) { throw new AssertionError("Must not write to changed destination"); }
            public String checkpointIdentity() { return "different-target"; }
        };
        reject(() -> useCase(source, changedDestination, store).poll(STREAM, 100));
        check(store.checkpoint.completedThrough() == 159 && writes.get() == 1);
        Store failingStore = new Store();
        reject(() -> useCase(source, batch -> { throw new Failure(Failure.Code.SINK_FAILED); }, failingStore).poll(STREAM, 100));
        check(failingStore.checkpoint.completedThrough() == 99);
        reject(() -> useCase((stream, window) -> { throw new Failure(Failure.Code.SOURCE_FAILED); }, batch -> writes.incrementAndGet(), failingStore).poll(STREAM, 100));
        check(failingStore.checkpoint.completedThrough() == 99 && writes.get() == 1);
        reject(() -> useCase((stream, window) -> slice(Map.of("tenant_id", "other"), window, List.of()), batch -> writes.incrementAndGet(), failingStore).poll(STREAM, 100));
        check(failingStore.checkpoint.completedThrough() == 99 && writes.get() == 1);
        reject(() -> useCase((stream, window) -> new Slice(LABELS, 1, 1, new HistoryPage(List.of(), new HistoryCursor(100, 0), false)),
            batch -> writes.incrementAndGet(), failingStore).poll(STREAM, 100));
        check(failingStore.checkpoint.completedThrough() == 99);
        reject(() -> useCase((stream, window) -> slice(LABELS, window, List.of(point(100, "0.25"),
            new MetricPoint(Instant.ofEpochSecond(100, 1), new BigDecimal("0.30")))), batch -> writes.incrementAndGet(), failingStore).poll(STREAM, 100));
        check(writes.get() == 1);
        var empty = useCase((stream, window) -> slice(LABELS, window, List.of()), batch -> writes.incrementAndGet(), failingStore).poll(STREAM, 100);
        check(empty.accepted() && empty.confirmedPoints() == 0 && failingStore.checkpoint.completedThrough() == 159 && writes.get() == 1);
        reject(() -> new IngestMetricHistoryUseCase((stream, window) -> slice(LABELS, window, List.of()),
            batch -> {}, new Store(), new HistoryPollPolicy(60, 120, 10, 2),
            Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC)).poll(STREAM, 100));
        System.out.println("HistoryIngestionSmoke: " + checks + " checks passed");
    }
    private static Slice slice(Map<String, String> labels, HistoryWindow window, List<MetricPoint> points) {
        return new Slice(labels, 1, 1, new HistoryPage(points, new HistoryCursor(window.till(), 999_999_999), true));
    }
    private static MetricPoint point(long clock, String value) { return new MetricPoint(Instant.ofEpochSecond(clock), new BigDecimal(value)); }
    private static IngestMetricHistoryUseCase useCase(TestSource source, TestSink sink, Store store) {
        return new IngestMetricHistoryUseCase(source, sink, store, new HistoryPollPolicy(60, 120, 10, 2),
            Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
    }
    private interface TestSource extends SourceReader {
        @Override default String checkpointIdentity() { return "labeled-test-source"; }
    }
    private interface TestSink extends MetricSink {
        @Override default String checkpointIdentity() { return "labeled-test-sink"; }
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Check " + checks); checks++; }
    private static void reject(Runnable work) { try { work.run(); } catch (Failure expected) { checks++; return; } throw new AssertionError("Expected failure"); }
    private static final class Store implements CheckpointStore {
        HistoryCheckpoint checkpoint = HistoryCheckpoint.initial(100);
        public Result withLock(HistoryStream stream, long initialFrom, Function<HistoryCheckpoint, Update> work) {
            Update update = work.apply(checkpoint);
            checkpoint = update.checkpoint();
            return update.result();
        }
    }
}
