package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.domain.RawRetention;
import com.acme.opsweave.integration.domain.ScanRunRetention;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL", matches=".+")
class PostgresRawRetentionIT extends OwnedInventoryTest {
    final TenantId tenant = new TenantId("raw-retention-" + UUID.randomUUID());
    final UUID run = UUID.randomUUID();
    final DriverManagerDataSource dataSource = new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),
        System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    final InventoryWiring wiring = openInventory(new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")),
        new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "zabbix-1", 1),
        new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"),
            System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
    static Connector.RawRecord raw(String value) { return new Connector.RawRecord("host-1", Instant.now(), Map.of("padding", value)); }
    PostgresSyncStore store(int source, int all) { return new PostgresSyncStore(dataSource, ScanRunRetention.Policy.defaults(), new RawRetention.Policy(source, all)); }
    long rows() throws Exception {
        try (var db = dataSource.getConnection(); var query = db.prepareStatement("SELECT count(*) FROM integration.raw_record_metadata WHERE tenant_id=?")) {
            query.setString(1, tenant.value()); try (var result = query.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    @Test void scopeAndTenantCapacityFailWithoutEvictingOriginalReferences() throws Exception {
        var store = store(2, 3);
        String first = store.retain(tenant, "source-1", run, raw("first"));
        String second = store.retain(tenant, "source-1", run, raw("second"));
        assertEquals(RawRetention.Reason.SOURCE_LIMIT,
            assertThrows(RawRetention.Limit.class, () -> store.retain(tenant, "source-1", UUID.randomUUID(), raw("rejected"))).reason());
        store.retain(tenant, "source-2", run, raw("third"));
        assertEquals(RawRetention.Reason.TENANT_LIMIT,
            assertThrows(RawRetention.Limit.class, () -> store.retain(tenant, "source-3", run, raw("rejected"))).reason());
        assertEquals(3, rows(), "both failed inserts roll back");
        var reopened = store(2, 3);
        var retained = reopened.read(tenant, "source-1", run, 100);
        assertEquals(Set.of(first, second), new HashSet<>(retained.records().stream().map(r -> r.ref()).toList()));
        assertEquals(Set.of("first", "second"), new HashSet<>(retained.records().stream().map(r -> (String) r.record().payload().get("padding")).toList()));
        assertEquals(retained, reopened.read(tenant, "source-1", run, 100), "reads never delete or rewrite retained Raw");
        var other = new TenantId("raw-retention-other-" + UUID.randomUUID());
        reopened.retain(other, "source-1", run, raw("other tenant"));
        assertEquals(1, reopened.read(other, "source-1", run, 100).retainedCount());
        assertEquals(3, rows(), "another tenant never spends this tenant's capacity");
    }

    @Test void serializedAndNormalizedUtf8SizeAreCheckedBeforeAnyInsert() throws Exception {
        var store = store(2, 3);
        for (String oversized : List.of("测".repeat(22000), "x".repeat(65522))) {
            // The ASCII case is 65536 serialized bytes but 65537 after JSONB normalization.
            assertEquals(RawRetention.Reason.PAYLOAD_LIMIT,
                assertThrows(RawRetention.Limit.class, () -> store.retain(tenant, "source-1", run, raw(oversized))).reason());
        }
        assertEquals(0, rows(), "oversized writes do not consume row budget");
        String reference = store.retain(tenant, "source-1", run, raw("x".repeat(65521)));
        var read = store.read(tenant, "source-1", run, 100).records().getFirst();
        assertEquals(reference, read.ref());
        assertNotNull(read.record(), "the maximum admitted normalized payload remains readable");
    }

    @ParameterizedTest @ValueSource(booleans={true, false})
    void competingConnectionsCannotOverspendSourceOrSharedTenantCapacity(boolean sameSource) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                final String source = sameSource ? "shared-source" : "source-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try { store(3, 5).retain(tenant, source, run, raw("parallel fixture")); return true; }
                    catch (RawRetention.Limit rejected) { return false; }
                }));
            }
            start.countDown(); int accepted = 0;
            for (var result : results) if (result.get(20, TimeUnit.SECONDS)) accepted++;
            assertEquals(sameSource ? 3 : 5, accepted, "capacity is shared across distinct JDBC connections");
            assertEquals(accepted, rows(), "only admitted rows are actually committed");
        }
    }
}
