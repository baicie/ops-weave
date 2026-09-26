package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.inventory.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresSourceBindingCorrectionIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("correction-pg-"+UUID.randomUUID());
    final OpsweaveProperties properties=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);final String source="cmdb-import",namespace="assets";final Instant now=Instant.now().minusSeconds(60);
    record Seed(EntityId entity,AssetIdentity.Pin pin){}
    Seed seed(String host){var m=new ZabbixHostMapper().map(PipelineDefinition.zabbixHostV1(),tenant,"zabbix-1",Map.of("hostid",host,"host","primary-"+host,"status","0"),now,now,"raw-"+host);wiring.writer().upsert(m.entity(),m.observation(),m.link());return claim(m.entity().id());}
    Seed claim(EntityId entity){var id=UUID.randomUUID();var r=wiring.assetIdentities().change(tenant,entity,namespace,new AssetIdentity.Command(id,AssetIdentity.Action.ASSERT,id,version(entity),UUID.randomUUID().toString(),"operator","Verified asset UUID"),now);return new Seed(entity,r.identity().pin());}
    long version(EntityId entity){return wiring.query().find(tenant,entity).orElseThrow().version();}
    SourceSnapshot.Receipt snapshot(Seed seed,String external,Instant at){return wiring.sourceSnapshots().ingest(tenant,"operator",source,namespace,new SourceSnapshot.Input(UUID.randomUUID(),at,false,List.of(new SourceSnapshot.Row(external,seed.pin().value(),Map.of("owner","Source owner")))),CmdbImportPipeline.DIGEST);}
    SourceBindingCorrection.Command command(Seed from,Seed to,SourceSnapshot.Receipt before,Instant at){return new SourceBindingCorrection.Command(UUID.randomUUID(),before.input().records().getFirst().externalId(),before.input().requestId(),from.entity(),version(from.entity()),to.entity(),version(to.entity()),to.pin(),at,Map.of("owner","Corrected owner"),"Verified external object belongs to target UUID");}
    SourceBindingCorrection.Receipt correct(SourceBindingCorrection.Command c){return wiring.sourceSnapshots().correct(tenant,"operator",source,namespace,c,CmdbImportPipeline.DIGEST);}
    void retire(){var lease=wiring.writer().beginScan(new SourceScan.Scope(tenant,"zabbix-1","host"),UUID.randomUUID());wiring.writer().finishScan(lease,Set.of());}
    @Test void correctionChangesOnlyCurrentBindingAndKeepsOriginalObservationsAndReceipts()throws Exception{
        var from=seed("1");var to=seed("2");var before=snapshot(from,"external",now);retire();var c=command(from,to,before,now.plusSeconds(1));var receipt=correct(c);
        assertEquals("INACTIVE",wiring.query().find(tenant,from.entity()).orElseThrow().lifecycle());assertEquals("ACTIVE",wiring.query().find(tenant,to.entity()).orElseThrow().lifecycle());
        assertEquals(c.expectedPreviousVersion()+1,version(from.entity()));assertEquals(c.expectedTargetVersion()+1,version(to.entity()));
        assertTrue(wiring.sourceSnapshots().presence(tenant,from.entity(),source).items().isEmpty());assertEquals(c.requestId(),wiring.sourceSnapshots().presence(tenant,to.entity(),source).items().getFirst().presence().snapshotId());
        assertEquals(before,wiring.sourceSnapshots().receipt(tenant,"operator",source,namespace,before.input().requestId()).orElseThrow());
        assertEquals(receipt,openInventory(properties).sourceSnapshots().correction(tenant,"operator",source,namespace,c.requestId()).orElseThrow());
        assertTrue(wiring.sourceSnapshots().correction(tenant,"other",source,namespace,c.requestId()).isEmpty());assertTrue(wiring.sourceSnapshots().correction(new TenantId("other-tenant"),"operator",source,namespace,c.requestId()).isEmpty());
        assertEquals(List.of(receipt),wiring.sourceSnapshots().corrections(tenant,source,namespace,from.entity(),null,25));assertEquals(List.of(receipt),wiring.sourceSnapshots().corrections(tenant,source,namespace,to.entity(),null,25));assertTrue(wiring.sourceSnapshots().corrections(tenant,source,namespace,to.entity(),c.requestId(),25).isEmpty());
        assertNull(wiring.query().find(tenant,to.entity()).orElseThrow().attributes().get("owner"));
        try(var db=java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));var s=db.prepareStatement("SELECT entity_id FROM inventory.entity_observation WHERE tenant_id=? AND id=?")){
            s.setString(1,tenant.value());s.setString(2,"source-snapshot:"+before.resolved().getFirst().reviewId());try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(from.entity().value(),r.getObject(1,UUID.class));}}
        var dir=java.nio.file.Path.of("../../.tmp/source-correction-pg");java.nio.file.Files.createDirectories(dir);java.nio.file.Files.writeString(dir.resolve("source-binding-correction-receipt.json"),SourceReviewJson.JSON.writeValueAsString(SourceBindingCorrectionJson.wire(receipt)));
    }
    @Test void conflictingTargetRollsBackBindingRemovalAndOldLifecycleProjection(){
        var from=seed("1");var to=seed("2");var before=snapshot(from,"external",now);snapshot(to,"occupied",now.plusSeconds(1));retire();var old=wiring.query().find(tenant,from.entity()).orElseThrow();var c=command(from,to,before,now.plusSeconds(2));
        assertEquals(SourceSnapshot.Code.BINDING_CONFLICT,assertThrows(SourceSnapshot.Conflict.class,()->correct(c)).code());assertEquals(old,wiring.query().find(tenant,from.entity()).orElseThrow());
        assertEquals(before.input().requestId(),wiring.sourceSnapshots().presence(tenant,from.entity(),source).items().getFirst().presence().snapshotId());assertTrue(wiring.sourceSnapshots().correction(tenant,"operator",source,namespace,c.requestId()).isEmpty());
    }
    @Test void staleInputVersionPinAndRequestReuseNeverPartiallyCorrect(){
        var from=seed("1");var to=seed("2");var before=snapshot(from,"external",now);var c=command(from,to,before,now.plusSeconds(1));
        wiring.sourceSnapshots().ingest(tenant,"operator",source,namespace,new SourceSnapshot.Input(UUID.randomUUID(),now.plusSeconds(2),false,List.of()),CmdbImportPipeline.DIGEST);
        assertEquals(SourceSnapshot.Code.SNAPSHOT_OUTDATED,assertThrows(SourceSnapshot.Conflict.class,()->correct(c)).code());
        var stale=command(from,to,before,now.plusSeconds(3));claim(from.entity());assertEquals(SourceBindingCorrection.Code.BINDING_CHANGED,assertThrows(SourceBindingCorrection.Conflict.class,()->correct(stale)).code());
        var revoked=command(from,to,before,now.plusSeconds(4));wiring.assetIdentities().change(tenant,to.entity(),namespace,new AssetIdentity.Command(UUID.randomUUID(),AssetIdentity.Action.REVOKE,to.pin().id(),version(to.entity()),null,"operator","Wrong UUID"),Instant.now());
        var current=new SourceBindingCorrection.Command(revoked.requestId(),revoked.externalId(),revoked.expectedSnapshotId(),from.entity(),version(from.entity()),to.entity(),version(to.entity()),to.pin(),revoked.observedAt(),revoked.values(),revoked.reason());assertThrows(SourceReview.Conflict.class,()->correct(current));
        var collision=new SourceBindingCorrection.Command(before.input().requestId(),"external",before.input().requestId(),from.entity(),version(from.entity()),to.entity(),version(to.entity()),to.pin(),now.plusSeconds(5),Map.of("owner","x"),"reason");
        assertEquals(SourceBindingCorrection.Code.CORRECTION_REQUEST_CONFLICT,assertThrows(SourceBindingCorrection.Conflict.class,()->correct(collision)).code());assertEquals(before.input().requestId(),wiring.sourceSnapshots().presence(tenant,from.entity(),source).items().getFirst().presence().snapshotId());
    }
    @Test void concurrentIdenticalRequestsHaveOneEffectAndCompetingRequestsCannotReuseOldState()throws Exception{
        var from=seed("1");var to=seed("2");var third=seed("3");var before=snapshot(from,"external",now);var c=command(from,to,before,now.plusSeconds(1));var competing=command(from,third,before,now.plusSeconds(2));
        SourceBindingCorrection.Receipt receipt;try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->correct(c));var b=pool.submit(()->correct(c));receipt=a.get(10,TimeUnit.SECONDS);assertEquals(receipt,b.get(10,TimeUnit.SECONDS));}
        assertEquals(c.expectedTargetVersion()+1,version(to.entity()));assertThrows(SourceBindingCorrection.Conflict.class,()->correct(competing));
        assertThrows(SourceBindingCorrection.Conflict.class,()->wiring.sourceSnapshots().correct(tenant,"other",source,namespace,c,CmdbImportPipeline.DIGEST));
        assertEquals(receipt,correct(c));assertEquals(1,wiring.sourceSnapshots().corrections(tenant,source,namespace,to.entity(),null,25).size());
    }
    @Test void activeFieldsMustBeRevokedBeforeCorrection(){
        var from=seed("1");var to=seed("2");var before=snapshot(from,"external",now);var reviewId=before.resolved().getFirst().reviewId();
        wiring.sourceReviews().decide(tenant,from.entity(),source,reviewId,new SourceReview.Command(UUID.randomUUID(),SourceReview.Action.ACCEPT,version(from.entity()),1,Map.of("owner",SourceReview.Choice.SUPPLEMENTAL),"Reviewed field","operator"),Instant.now());
        assertEquals(SourceBindingCorrection.Code.BINDING_FIELDS_ACTIVE,assertThrows(SourceBindingCorrection.Conflict.class,()->correct(command(from,to,before,now.plusSeconds(1)))).code());
        wiring.sourceReviews().decide(tenant,from.entity(),source,reviewId,new SourceReview.Command(UUID.randomUUID(),SourceReview.Action.REVOKE,version(from.entity()),2,Map.of(),"Revoke incorrect source field","operator"),Instant.now());
        var r=correct(command(from,to,before,now.plusSeconds(2)));assertNull(wiring.query().find(tenant,from.entity()).orElseThrow().attributes().get("owner"));assertEquals(to.entity(),r.snapshot().resolved().getFirst().entityId());
    }
    @Test void sameEntityPinCorrectionBumpsOneVersionAndDoesNotMoveHistory(){
        var from=seed("1");var before=snapshot(from,"external",now);var to=claim(from.entity());
        assertEquals(SourceBindingCorrection.Code.BINDING_UNCHANGED,assertThrows(SourceBindingCorrection.Conflict.class,()->correct(command(from,from,before,now.plusSeconds(1)))).code());
        var c=command(from,to,before,now.plusSeconds(1));var r=correct(c);assertEquals(c.expectedPreviousVersion()+1,version(from.entity()));assertEquals(to.pin(),wiring.sourceSnapshots().presence(tenant,from.entity(),source).items().getFirst().presence().identity());assertEquals(from.pin(),r.previous().identity());
    }
}
