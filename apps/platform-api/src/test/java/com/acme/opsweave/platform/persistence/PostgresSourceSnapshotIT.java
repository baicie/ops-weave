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
class PostgresSourceSnapshotIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("snapshot-pg-"+UUID.randomUUID());
    final OpsweaveProperties properties=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);final String source="cmdb-import",namespace="enterprise-assets";final Instant now=Instant.now().minusSeconds(60);
    record Seed(EntityId entity,String value,AssetIdentity.Pin pin){}
    Seed seed(String host){var m=new ZabbixHostMapper().map(PipelineDefinition.zabbixHostV1(),tenant,"zabbix-1",Map.of("hostid",host,"host","primary","status","0"),now,now,"raw-"+host);wiring.writer().upsert(m.entity(),m.observation(),m.link());var id=UUID.randomUUID();String value=UUID.randomUUID().toString();var claim=wiring.assetIdentities().change(tenant,m.entity().id(),namespace,new AssetIdentity.Command(id,AssetIdentity.Action.ASSERT,id,1,value,"operator","Verified asset UUID"),now);return new Seed(m.entity().id(),value,claim.identity().pin());}
    SourceSnapshot.Input input(Instant at,boolean complete,Seed... seeds){return new SourceSnapshot.Input(UUID.randomUUID(),at,complete,Arrays.stream(seeds).map(s->new SourceSnapshot.Row("asset-"+s.value(),s.value(),Map.of("name","unreviewed name","owner","CMDB owner"))).toList());}
    SourceSnapshot.Receipt ingest(SourceSnapshot.Input input){return wiring.sourceSnapshots().ingest(tenant,"operator",source,namespace,input,CmdbImportPipeline.DIGEST);}
    void retire(){var lease=wiring.writer().beginScan(new SourceScan.Scope(tenant,"zabbix-1","host"),UUID.randomUUID());wiring.writer().finishScan(lease,Set.of());}
    @Test void resolvesRegisteredIdentityRetainsObservationAndRequiresReviewWhileProtectingOtherSourcePresence()throws Exception {
        var seed=seed("1");var first=ingest(input(now,true,seed));assertEquals(seed.entity(),first.resolved().getFirst().entityId());assertEquals("primary",wiring.query().find(tenant,seed.entity()).orElseThrow().name());
        var review=wiring.sourceReviews().reviews(tenant,seed.entity(),source,null,25).items().getFirst();assertEquals(SourceReview.Status.PENDING,review.status());assertEquals(seed.pin(),review.identity());assertEquals(3,review.baseVersion());
        retire();assertEquals("ACTIVE",wiring.query().find(tenant,seed.entity()).orElseThrow().lifecycle());
        ingest(input(now.plusSeconds(1),false));assertEquals("ACTIVE",wiring.query().find(tenant,seed.entity()).orElseThrow().lifecycle());
        var absent=ingest(input(now.plusSeconds(2),true));assertEquals(1,absent.markedAbsent());assertEquals("INACTIVE",wiring.query().find(tenant,seed.entity()).orElseThrow().lifecycle());
        assertEquals("ABSENT",wiring.sourceSnapshots().presence(tenant,seed.entity(),source).items().getFirst().presence().status(Instant.now(),true));
        var absentAgain=ingest(input(now.plusSeconds(3),true));assertEquals(1,absentAgain.markedAbsent());
        var confirmedAbsent=wiring.sourceSnapshots().presence(tenant,seed.entity(),source).items().getFirst().presence();assertEquals(now.plusSeconds(3),confirmedAbsent.observedAt());assertEquals(absentAgain.input().requestId(),confirmedAbsent.snapshotId());assertFalse(confirmedAbsent.present());
        var refreshed=ingest(input(now.plusSeconds(4),true,seed));assertEquals("ACTIVE",wiring.query().find(tenant,seed.entity()).orElseThrow().lifecycle());
        var freshReview=wiring.sourceReviews().reviews(tenant,seed.entity(),source,null,25).items().stream().filter(r->r.id().equals(refreshed.resolved().getFirst().reviewId())).findFirst().orElseThrow();
        wiring.sourceReviews().decide(tenant,seed.entity(),source,freshReview.id(),new SourceReview.Command(UUID.randomUUID(),SourceReview.Action.ACCEPT,freshReview.baseVersion(),1,Map.of("name",SourceReview.Choice.PRIMARY,"owner",SourceReview.Choice.SUPPLEMENTAL),"Verified fields","operator"),Instant.now());
        assertEquals("CMDB owner",wiring.query().find(tenant,seed.entity()).orElseThrow().attributes().get("owner"));
        var dir=java.nio.file.Path.of("../../.tmp/source-snapshot-pg");java.nio.file.Files.createDirectories(dir);java.nio.file.Files.writeString(dir.resolve("source-snapshot-receipt.json"),SourceReviewJson.JSON.writeValueAsString(SourceSnapshotJson.wire(refreshed)));
    }
    @Test void unresolvedOrLateBatchNeverMarksMissingOrPartiallyWrites(){
        var seed=seed("1");ingest(input(now,true,seed));retire();var before=wiring.query().find(tenant,seed.entity()).orElseThrow();
        var invalid=new ArrayList<>(input(now.plusSeconds(1),true,seed).records());invalid.add(new SourceSnapshot.Row("unknown",UUID.randomUUID().toString(),Map.of("name","primary")));
        assertEquals(SourceSnapshot.Code.IDENTITY_UNRESOLVED,assertThrows(SourceSnapshot.Conflict.class,()->ingest(new SourceSnapshot.Input(UUID.randomUUID(),now.plusSeconds(1),true,invalid))).code());
        assertEquals(before,wiring.query().find(tenant,seed.entity()).orElseThrow());
        assertEquals(SourceSnapshot.Code.SNAPSHOT_OUTDATED,assertThrows(SourceSnapshot.Conflict.class,()->ingest(input(now,true))).code());
        assertEquals(before,wiring.query().find(tenant,seed.entity()).orElseThrow());
    }
    @Test void concurrentSameRequestHasOneReceiptAndDifferentActorOrBodyCannotReuseIt()throws Exception {
        var seed=seed("1");var request=input(now,true,seed);SourceSnapshot.Receipt original;
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->ingest(request));var b=pool.submit(()->ingest(request));original=a.get(10,TimeUnit.SECONDS);assertEquals(original,b.get(10,TimeUnit.SECONDS));}
        assertEquals(3,wiring.query().find(tenant,seed.entity()).orElseThrow().version());assertEquals(1,wiring.sourceReviews().reviews(tenant,seed.entity(),source,null,25).items().size());
        assertEquals(original,openInventory(properties).sourceSnapshots().receipt(tenant,"operator",source,namespace,request.requestId()).orElseThrow());
        assertTrue(wiring.sourceSnapshots().receipt(tenant,"other",source,namespace,request.requestId()).isEmpty());
        assertThrows(SourceSnapshot.Conflict.class,()->wiring.sourceSnapshots().ingest(tenant,"other",source,namespace,request,CmdbImportPipeline.DIGEST));
        assertThrows(SourceSnapshot.Conflict.class,()->ingest(new SourceSnapshot.Input(request.requestId(),now,false,request.records())));
    }
    @Test void revokedIdentityImmediatelyStopsPresenceProtectionAndCannotSilentlyRebind(){
        var seed=seed("1");ingest(input(now,true,seed));retire();assertEquals("ACTIVE",wiring.query().find(tenant,seed.entity()).orElseThrow().lifecycle());
        long version=wiring.query().find(tenant,seed.entity()).orElseThrow().version();wiring.assetIdentities().change(tenant,seed.entity(),namespace,new AssetIdentity.Command(UUID.randomUUID(),AssetIdentity.Action.REVOKE,seed.pin().id(),version,null,"operator","Revoke wrong identity"),Instant.now());
        assertEquals("INACTIVE",wiring.query().find(tenant,seed.entity()).orElseThrow().lifecycle());
        assertTrue(wiring.query().page(tenant,new EntityVisibility(true,Set.of()),new EntityPageQuery("",Lifecycle.ACTIVE,"",null,10)).isEmpty());
        var p=wiring.sourceSnapshots().presence(tenant,seed.entity(),source).items().getFirst();assertEquals("IDENTITY_REVOKED",p.presence().status(Instant.now(),p.identityActive()));
        assertEquals(SourceSnapshot.Code.IDENTITY_UNRESOLVED,assertThrows(SourceSnapshot.Conflict.class,()->ingest(input(now.plusSeconds(1),true,seed))).code());
        var other=seed("2");var rebind=new SourceSnapshot.Input(UUID.randomUUID(),now.plusSeconds(2),true,List.of(new SourceSnapshot.Row("asset-"+seed.value(),other.value(),Map.of("owner","x"))));
        assertEquals(SourceSnapshot.Code.BINDING_CONFLICT,assertThrows(SourceSnapshot.Conflict.class,()->ingest(rebind)).code());
    }
    @Test void manualAndSnapshotBindingsCannotAssignAnExternalObjectOrEntityToDifferentTargets(){
        var first=seed("1");var second=seed("2");var third=seed("3");ingest(input(now,true,first));
        // Manual review cannot take a snapshot's external object, even though its fields are still pending.
        assertThrows(SourceReview.Conflict.class,()->acceptManual(second,"asset-"+first.value()));
        // Nor can it assign another external object to the already bound asset.
        assertThrows(SourceReview.Conflict.class,()->acceptManual(first,"another-object"));
        assertEquals("primary",wiring.query().find(tenant,first.entity()).orElseThrow().name());
        assertEquals("primary",wiring.query().find(tenant,second.entity()).orElseThrow().name());
        acceptManual(third,"manual-third");
        // Reverse direction: snapshot cannot bind another external object to a manually bound asset.
        assertEquals(SourceSnapshot.Code.BINDING_CONFLICT,assertThrows(SourceSnapshot.Conflict.class,()->ingest(input(now.plusSeconds(1),false,third))).code());
        var otherTarget=new SourceSnapshot.Input(UUID.randomUUID(),now.plusSeconds(1),false,List.of(new SourceSnapshot.Row("manual-third",second.value(),Map.of("name","wrong target"))));
        assertEquals(SourceSnapshot.Code.BINDING_CONFLICT,assertThrows(SourceSnapshot.Conflict.class,()->ingest(otherTarget)).code());
        assertTrue(wiring.sourceSnapshots().presence(tenant,second.entity(),source).items().isEmpty());
        assertEquals("manual accepted",wiring.query().find(tenant,third.entity()).orElseThrow().name());
    }
    void acceptManual(Seed seed,String external){
        long version=wiring.query().find(tenant,seed.entity()).orElseThrow().version();
        var review=wiring.sourceReviews().stage(tenant,seed.entity(),new com.acme.opsweave.inventory.api.SourceReviewStore.Import(UUID.randomUUID(),version,new ExternalObjectKey(tenant,source,"cmdb-host",external,"1"),now,Map.of("name","manual accepted"),CmdbImportPipeline.DIGEST,"operator",null),Instant.now());
        wiring.sourceReviews().decide(tenant,seed.entity(),source,review.id(),new SourceReview.Command(UUID.randomUUID(),SourceReview.Action.ACCEPT,version,1,Map.of("name",SourceReview.Choice.SUPPLEMENTAL),"Verified fields","operator"),Instant.now());
    }
    @Test void expiryIsAppliedBeforeLifecyclePaginationAndThePresenceReadReportsStale()throws Exception {
        var seed=seed("1");ingest(input(now,true,seed));retire();
        var old=wiring.sourceSnapshots().presence(tenant,seed.entity(),source).items().getFirst().presence();var past=Instant.now().minus(SourceSnapshot.MAX_AGE).minusSeconds(1);
        var expired=new SourceSnapshot.Presence(tenant,seed.entity(),source,old.externalId(),old.identity(),past,old.ingestedAt(),true,old.snapshotId());
        try(var c=java.sql.DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));var s=c.prepareStatement("UPDATE inventory.entity_source_presence SET expires_epoch_nanos=?,body=?::jsonb WHERE tenant_id=?")){
            s.setBigDecimal(1,PostgresInventoryStore.nanos(expired.expiresAt()));s.setString(2,SourceReviewJson.JSON.writeValueAsString(SourceSnapshotJson.wire(expired)));s.setString(3,tenant.value());assertEquals(1,s.executeUpdate());}
        assertEquals("INACTIVE",wiring.query().find(tenant,seed.entity()).orElseThrow().lifecycle());
        assertTrue(wiring.query().page(tenant,new EntityVisibility(true,Set.of()),new EntityPageQuery("",Lifecycle.ACTIVE,"",null,10)).isEmpty());
        assertEquals(1,wiring.query().page(tenant,new EntityVisibility(true,Set.of()),new EntityPageQuery("",Lifecycle.INACTIVE,"",null,10)).size());
        var page=wiring.sourceSnapshots().presence(tenant,seed.entity(),source);var p=page.items().getFirst();assertEquals("STALE",p.presence().status(page.evaluatedAt(),p.identityActive()));
    }
}
