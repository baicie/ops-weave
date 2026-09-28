import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.domain.RawRetention;
import com.acme.opsweave.integration.infrastructure.InMemoryRawRecordStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class RawRetentionSmoke {
    private static int checks;
    private static final Connector.RawRecord RAW = new Connector.RawRecord("host-1", Instant.now(), Map.of("hostid", "1"));
    public static void main(String[] args) throws Exception {
        var tenant = new TenantId("raw-budget-fixture");
        var run = UUID.randomUUID();
        var store = new InMemoryRawRecordStore(new RawRetention.Policy(2, 3));
        String first = store.retain(tenant, "source-1", run, RAW);
        String second = store.retain(tenant, "source-1", run, RAW);
        limited(RawRetention.Reason.SOURCE_LIMIT, () -> store.retain(tenant, "source-1", UUID.randomUUID(), RAW));
        require(store.read(tenant, "source-1", run, 100).records().stream().map(r -> r.ref()).toList().equals(List.of(first, second)),
            "a full source preserves both original Raw references without eviction");
        store.retain(tenant, "source-2", run, RAW);
        limited(RawRetention.Reason.TENANT_LIMIT, () -> store.retain(tenant, "source-3", run, RAW));
        require(store.read(tenant, "source-2", run, 100).retainedCount() == 1, "another source shares the tenant budget");
        var other = new TenantId("raw-budget-other");
        store.retain(other, "source-1", run, RAW);
        require(store.read(other, "source-1", run, 100).retainedCount() == 1, "another tenant keeps its own budget");
        require(store.read(tenant, "source-3", run, 100).retainedCount() == 0, "rejected insert leaves no row");
        require(store.read(tenant, "source-1", UUID.randomUUID(), 100).retainedCount() == 0, "run scope is preserved");
        limited(RawRetention.Reason.PAYLOAD_LIMIT, () -> store.retain(other, "source-2", run,
            new Connector.RawRecord("large", Instant.now(), Map.of("fixture", "x".repeat(65_536)))));
        require(store.read(other, "source-2", run, 100).retainedCount() == 0, "oversized data does not consume capacity");
        for (int[] invalid : List.of(new int[]{0, 2}, new int[]{1001, 5000}, new int[]{1, 5001}, new int[]{2, 1})) {
            boolean rejected = false;
            try { new RawRetention.Policy(invalid[0], invalid[1]); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "a policy cannot widen the published capacity or be inconsistent");
        }
        var concurrent = new InMemoryRawRecordStore(new RawRetention.Policy(3, 3));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var writes = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) writes.add(pool.submit(() -> {
                start.await();
                try { concurrent.retain(tenant, "source-1", run, RAW); return true; }
                catch (RawRetention.Limit expected) { return false; }
            }));
            start.countDown(); int accepted = 0;
            for (var write : writes) if (write.get(10, TimeUnit.SECONDS)) accepted++;
            require(accepted == 3, "concurrent writers cannot each spend the same capacity");
        }
        require(concurrent.read(tenant, "source-1", run, 100).retainedCount() == 3, "concurrent writes remain bounded");
        System.out.println("Raw retention smoke: " + checks + " checks passed");
    }
    private static void limited(RawRetention.Reason reason, Runnable work) {
        try { work.run(); throw new AssertionError("Raw write should be rejected"); }
        catch (RawRetention.Limit rejected) { require(rejected.reason() == reason, "stable rejection reason"); }
    }
    private static void require(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
}
