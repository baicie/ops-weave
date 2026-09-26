package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresObservationIT extends OwnedInventoryTest {
    private final TenantId tenant = new TenantId("observation-" + UUID.randomUUID());
    private final OpsweaveProperties properties = new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed", true, new OpsweaveProperties.Auth.Dev("", "", tenant.value(), "", "")),
        new OpsweaveProperties.Zabbix("fixture", "", "env:OPSWEAVE_ZABBIX_TOKEN", "zabbix-1", 1),
        new OpsweaveProperties.Inventory("postgres", System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    private final InventoryWiring wiring = openInventory(properties);
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:10.987654321Z");
    private final ExternalObjectKey key = new ExternalObjectKey(tenant, "zabbix-1", "host", "10084", "1");
    private final EntityId id = EntityIds.fromExternal(key);
    private Observation observation(String ref, Instant observed, Instant ingested) { return new Observation(ref, key, id, observed, ingested, Map.of("revision", 1, "dataMode", "labeled-fixture"), "raw-" + ref, 1); }
    private Entity entity(EntityId target, String name, Instant observed) { return new Entity(target, tenant, "host", name, Lifecycle.ACTIVE, 1, observed, Map.of("ip", "10.0.0.1")); }
    private ObservationQuery query(String after, int limit, Instant cutoff) { return new ObservationQuery(NOW.minusSeconds(60).getEpochSecond(), NOW.getEpochSecond(), cutoff, "", after, limit); }
    @Test void concurrentDuplicateIsAtomicAndExactTimesSurviveAdapterReopen() throws Exception {
        var o = observation("same", NOW.minusSeconds(1), NOW); var e = entity(id, "fixture", o.observedAt()); var link = new ExternalLink(id, key);
        try (var workers = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Future<?>>(); for (int i = 0; i < 6; i++) tasks.add(workers.submit(() -> wiring.writer().upsert(e, o, link)));
            for (var task : tasks) task.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, wiring.query().find(tenant, id).orElseThrow().version());
        var reopened = openInventory(properties); var entries = reopened.observations().observations(tenant, id, query(null, 25, NOW));
        assertEquals(1, entries.size()); assertEquals(o, entries.getFirst().observation()); assertTrue(entries.getFirst().exactTime());
        assertThrows(IllegalStateException.class, () -> reopened.writer().upsert(entity(id, "tampered", NOW), observation("same", NOW, NOW), link));
        assertEquals("fixture", reopened.query().find(tenant, id).orElseThrow().name());
    }
    @Test void filtersBeforePaginationPreservesLateObservationsAndDistinguishesLegacyPrecision() throws Exception {
        var a = observation("a", NOW.minusSeconds(1), NOW.minusNanos(1)); var b = observation("b", NOW.minusSeconds(20), NOW);
        wiring.writer().upsert(entity(id, "latest", a.observedAt()), a, new ExternalLink(id, key));
        wiring.writer().upsert(entity(id, "older", b.observedAt()), b, new ExternalLink(id, key));
        assertEquals("latest", wiring.query().find(tenant, id).orElseThrow().name()); assertEquals(1, wiring.query().find(tenant, id).orElseThrow().version());
        assertEquals(List.of(a), wiring.observations().observations(tenant, id, query(null, 25, NOW.minusNanos(1))).stream().map(v -> v.observation()).toList());
        assertEquals(b, wiring.observations().observations(tenant, id, query("a", 1, NOW)).getFirst().observation());
        assertTrue(wiring.observations().observations(new TenantId("other"), id, query(null, 25, NOW)).isEmpty());
        assertTrue(wiring.observations().observations(tenant, id, new ObservationQuery(NOW.minusSeconds(60).getEpochSecond(), NOW.getEpochSecond(), NOW, "zabbix-2", null, 1)).isEmpty());
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"), System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
             var statement = connection.prepareStatement("UPDATE inventory.entity_observation SET exact_time = false, observed_epoch_nanos = extract(epoch FROM observed_at) * 1000000000 WHERE tenant_id = ? AND id = 'b'")) {
            statement.setString(1, tenant.value()); assertEquals(1, statement.executeUpdate());
        }
        var legacy = wiring.observations().observations(tenant, id, query("a", 25, NOW)).getFirst();
        assertFalse(legacy.exactTime()); assertEquals(0, legacy.observation().observedAt().getNano() % 1000);
    }
    @Test void conflictingExternalLinkRollsBackBothEntityAndObservationAndNoImplicitFusion() {
        var a = observation("a", NOW.minusSeconds(1), NOW); wiring.writer().upsert(entity(id, "original", a.observedAt()), a, new ExternalLink(id, key));
        var target = new EntityId(UUID.randomUUID()); var reassigned = new Observation("reassigned", key, target, NOW, NOW, Map.of(), "raw-reassigned", 1);
        assertThrows(IllegalStateException.class, () -> wiring.writer().upsert(entity(target, "invalid", NOW), reassigned, new ExternalLink(target, key)));
        assertTrue(wiring.query().find(tenant, target).isEmpty()); assertTrue(wiring.observations().observations(tenant, target, query(null, 25, NOW)).isEmpty());
        var otherKey = new ExternalObjectKey(tenant, "zabbix-2", "host", "10084", "1");
        var another = new Observation("other-source", otherKey, id, NOW, NOW, Map.of(), "raw-other", 1);
        assertThrows(IllegalStateException.class, () -> wiring.writer().upsert(entity(id, "unreviewed fusion", NOW), another, new ExternalLink(id, otherKey)));
        assertEquals("original", wiring.query().find(tenant, id).orElseThrow().name()); assertEquals(1, wiring.observations().observations(tenant, id, query(null, 25, NOW)).size());
    }
}
