package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.api.SourceReviewStore;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.integration.domain.CmdbImportPipeline;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.inventory.SourceReviewJson;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL", matches=".+")
class PostgresSourceReviewIT extends OwnedInventoryTest {
    final TenantId tenant = new TenantId("review-pg-" + UUID.randomUUID());
    final OpsweaveProperties properties = new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),
        new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring = openInventory(properties);
    final Instant now = Instant.parse("2026-09-25T00:00:10.987654321Z");
    final String source = "cmdb-import-dev";
    ExternalObjectKey key(String host) { return new ExternalObjectKey(tenant,"zabbix-1","host",host,"1"); }
    void ingest(String host, String name, String ref, Instant at) {
        var key = key(host); var id = EntityIds.fromExternal(key); var attrs = Map.<String,Object>of("owner",name + " owner","ip","10.0.0.1");
        wiring.writer().upsert(new Entity(id,tenant,"host",name,Lifecycle.ACTIVE,1,at,attrs),new Observation(ref,key,id,at,at,attrs,"raw:"+ref,1),new ExternalLink(id,key));
    }
    SourceReview stage(String host, String external) { return wiring.sourceReviews().stage(tenant,EntityIds.fromExternal(key(host)),new SourceReviewStore.Import(UUID.randomUUID(),1,
        new ExternalObjectKey(tenant,source,"cmdb-host",external,"1"),now,Map.of("name","CMDB name","owner","CMDB owner"),CmdbImportPipeline.DIGEST,"operator"),now); }
    SourceReview.Command command(SourceReview.Action action, long entityVersion, int version) { return new SourceReview.Command(UUID.randomUUID(),action,entityVersion,version,
        action == SourceReview.Action.ACCEPT ? Map.of("name",SourceReview.Choice.SUPPLEMENTAL,"owner",SourceReview.Choice.PRIMARY) : Map.of(),"human confirmation","operator"); }
    @Test void concurrentIdempotentAcceptanceAndReopenRevokeRestoreLatestPrimary() throws Exception {
        ingest("1","original","a",now); var r = stage("1","cmdb-1"); var id = r.entityId(); var cmd = command(SourceReview.Action.ACCEPT,1,1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<SourceReview>>();
            for (int i=0;i<6;i++) futures.add(pool.submit(() -> wiring.sourceReviews().decide(tenant,id,source,r.id(),cmd,now)));
            var first = futures.getFirst().get(20,TimeUnit.SECONDS); for (var future : futures) assertEquals(first,future.get(20,TimeUnit.SECONDS));
        }
        assertEquals(2,wiring.query().find(tenant,id).orElseThrow().version());
        ingest("1","new primary","b",now.plusNanos(1)); ingest("1","late old","c",now.minusSeconds(1));
        var e = wiring.query().find(tenant,id).orElseThrow(); assertEquals("CMDB name",e.name()); assertEquals("new primary owner",e.attributes().get("owner")); assertEquals(3,e.version());
        var reopened = openInventory(properties); var active = reopened.sourceReviews().reviews(tenant,id,source,null,25).active();
        assertNotNull(active); assertEquals(now,active.observedAt()); assertEquals("original",active.primaryAtImport().get("name"));
        var revoked = reopened.sourceReviews().decide(tenant,id,source,r.id(),command(SourceReview.Action.REVOKE,3,2),now.plusSeconds(1));
        assertEquals(SourceReview.Status.REVOKED,revoked.status()); assertEquals("new primary",reopened.query().find(tenant,id).orElseThrow().name());
        assertFalse(reopened.query().find(tenant,id).orElseThrow().attributes().containsKey("fieldAuthority"));
        assertEquals(active,reopened.sourceReviews().decide(tenant,id,source,r.id(),cmd,now.plusSeconds(2)));
        assertEquals(4,reopened.query().find(tenant,id).orElseThrow().version());
        var observations = reopened.observations().observations(tenant,id,new ObservationQuery(now.minusSeconds(60).getEpochSecond(),now.plusSeconds(1).getEpochSecond(),now.plusSeconds(2),source,null,25));
        assertEquals(1,observations.size()); assertEquals("import",observations.getFirst().observation().fields().get("dataMode"));
        java.nio.file.Path out = java.nio.file.Path.of("../../.tmp/source-review-pg"); java.nio.file.Files.createDirectories(out);
        java.nio.file.Files.writeString(out.resolve("source-review.json"),SourceReviewJson.encode(revoked));
    }
    @Test void twoEntitiesCannotAcceptTheSameSupplementalObjectAndLoserRollsBack() throws Exception {
        ingest("1","first","a",now); ingest("2","second","b",now); var a = stage("1","same"); var b = stage("2","same");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1); var results = new ArrayList<Future<Boolean>>();
            for (var r : List.of(a,b)) results.add(pool.submit(() -> { gate.await(); try { wiring.sourceReviews().decide(tenant,r.entityId(),source,r.id(),command(SourceReview.Action.ACCEPT,1,1),now); return true; } catch (SourceReview.Conflict expected) { return false; } }));
            gate.countDown(); assertNotEquals(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS));
        }
        var ea = wiring.query().find(tenant,a.entityId()).orElseThrow(); var eb = wiring.query().find(tenant,b.entityId()).orElseThrow();
        assertEquals(3,ea.version()+eb.version());
        assertEquals(1,List.of(a,b).stream().filter(r -> wiring.sourceReviews().reviews(tenant,r.entityId(),source,null,25).active() != null).count());
    }
    @Test void staleVersionsAndSourceScopeFailWithoutChangesAndReconcileRemainsPrimary() {
        ingest("1","first","a",now); var r=stage("1","same"); var id=r.entityId();
        assertThrows(SourceReview.Conflict.class,()->wiring.sourceReviews().decide(tenant,id,"other",r.id(),command(SourceReview.Action.ACCEPT,1,1),now));
        assertTrue(wiring.sourceReviews().reviews(tenant,id,"other",null,25).items().isEmpty());
        assertTrue(wiring.sourceReviews().reviews(new TenantId("foreign"),id,source,null,25).items().isEmpty());
        assertThrows(SourceReview.Conflict.class,()->wiring.sourceReviews().decide(tenant,id,source,r.id(),command(SourceReview.Action.ACCEPT,1,1),now.plus(SourceReview.MAX_AGE)));
        wiring.sourceReviews().decide(tenant,id,source,r.id(),command(SourceReview.Action.ACCEPT,1,1),now);
        assertEquals(1,wiring.writer().retireMissing(tenant,"zabbix-1","host",Set.of()));
        wiring.sourceReviews().decide(tenant,id,source,r.id(),command(SourceReview.Action.REVOKE,3,2),now.plusSeconds(1));
        assertEquals("INACTIVE",wiring.query().find(tenant,id).orElseThrow().lifecycle());
        assertEquals("first",wiring.query().find(tenant,id).orElseThrow().name());
    }
    @Test void importReceiptIsImmutableAndConcurrentPrimaryChangePreventsAcceptance() {
        ingest("1","first","a",now); var id=EntityIds.fromExternal(key("1"));
        var input = new SourceReviewStore.Import(UUID.randomUUID(),1,new ExternalObjectKey(tenant,source,"cmdb-host","raw","1"),now,Map.of("owner","original raw"),CmdbImportPipeline.DIGEST,"operator");
        var r=wiring.sourceReviews().stage(tenant,id,input,now); assertEquals(r,wiring.sourceReviews().stage(tenant,id,input,now.plusSeconds(1)));
        assertThrows(SourceReview.Conflict.class,()->wiring.sourceReviews().stage(tenant,id,new SourceReviewStore.Import(input.id(),1,input.source(),now,Map.of("owner","changed"),input.mappingDigest(),input.actor()),now));
        ingest("1","newer","b",now.plusNanos(1));
        assertThrows(SourceReview.Conflict.class,()->wiring.sourceReviews().decide(tenant,id,source,r.id(),new SourceReview.Command(UUID.randomUUID(),SourceReview.Action.ACCEPT,2,1,Map.of("owner",SourceReview.Choice.SUPPLEMENTAL),"checked","operator"),now.plusSeconds(1)));
        assertEquals("newer",wiring.query().find(tenant,id).orElseThrow().name()); assertNull(wiring.sourceReviews().reviews(tenant,id,source,null,25).active());
    }
    @Test void corruptedPrimarySnapshotCannotRedirectRevokeToAnotherEntity() throws Exception {
        ingest("1","first","a",now); ingest("2","second","b",now);
        var r=stage("1","cmdb-1"); var other=EntityIds.fromExternal(key("2"));
        wiring.sourceReviews().decide(tenant,r.entityId(),source,r.id(),command(SourceReview.Action.ACCEPT,1,1),now);
        try (var c=java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
             var s=c.prepareStatement("UPDATE inventory.entity_source_authority SET primary_snapshot = jsonb_set(primary_snapshot, '{id}', to_jsonb(?::text)) WHERE tenant_id = ? AND entity_id = ?")) {
            s.setString(1,other.value().toString()); s.setString(2,tenant.value()); s.setObject(3,r.entityId().value()); assertEquals(1,s.executeUpdate());
        }
        assertThrows(IllegalStateException.class,()->wiring.sourceReviews().decide(tenant,r.entityId(),source,r.id(),command(SourceReview.Action.REVOKE,2,2),now));
        assertEquals("second",wiring.query().find(tenant,other).orElseThrow().name()); assertEquals(1,wiring.query().find(tenant,other).orElseThrow().version());
        assertEquals("CMDB name",wiring.query().find(tenant,r.entityId()).orElseThrow().name());
        assertNotNull(wiring.sourceReviews().reviews(tenant,r.entityId(),source,null,25).active());
    }
}
