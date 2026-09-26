package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.api.SourceReviewStore;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.integration.domain.CmdbImportPipeline;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresAssetIdentityIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("identity-pg-"+UUID.randomUUID());
    final OpsweaveProperties properties=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),
        new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties); final Instant now=Instant.parse("2026-09-25T00:00:00.123456789Z");
    final String namespace="enterprise-assets", value=UUID.randomUUID().toString(), source="cmdb-import";
    EntityId seed(String host){var key=new ExternalObjectKey(tenant,"zabbix-1","host",host,"1");var id=EntityIds.fromExternal(key);var fields=Map.<String,Object>of("ip","10.0.0.1","owner","primary owner");
        wiring.writer().upsert(new Entity(id,tenant,"host","Same name",Lifecycle.ACTIVE,1,now,fields),new Observation("seed-"+host,key,id,now,now,fields,"raw:"+host,1),new ExternalLink(id,key));return id;}
    AssetIdentity.Command claim(long version,String key){var id=UUID.randomUUID();return new AssetIdentity.Command(id,AssetIdentity.Action.ASSERT,id,version,key,"operator","Verified register");}
    AssetIdentity.Command revoke(UUID id,long version){return new AssetIdentity.Command(UUID.randomUUID(),AssetIdentity.Action.REVOKE,id,version,null,"operator","Correct mistaken identity");}
    SourceReviewStore.Import input(AssetIdentity.Pin pin,long version){return new SourceReviewStore.Import(UUID.randomUUID(),version,new ExternalObjectKey(tenant,source,"cmdb-host","asset-1","1"),now,Map.of("owner","CMDB owner"),CmdbImportPipeline.DIGEST,"operator",pin);}
    SourceReview.Command decide(SourceReview.Action action,long version,int review){return new SourceReview.Command(UUID.randomUUID(),action,version,review,action==SourceReview.Action.ACCEPT?Map.of("owner",SourceReview.Choice.SUPPLEMENTAL):Map.of(),"Verified fields","operator");}
    @Test void concurrentIdempotentAssertionAndReopenRetainOriginalReceipt() throws Exception {
        var entity=seed("1");var command=claim(1,value);
        try(var pool=Executors.newFixedThreadPool(4)){
            var calls=new ArrayList<Future<com.acme.opsweave.inventory.api.AssetIdentityStore.Receipt>>();
            for(int i=0;i<4;i++)calls.add(pool.submit(()->wiring.assetIdentities().change(tenant,entity,namespace,command,now)));
            var first=calls.getFirst().get(20,TimeUnit.SECONDS);for(var call:calls)assertEquals(first,call.get(20,TimeUnit.SECONDS));
        }
        assertEquals(2,wiring.query().find(tenant,entity).orElseThrow().version());
        var reopened=openInventory(properties);var stored=reopened.assetIdentities().resolve(tenant,namespace,value).orElseThrow();assertEquals(now,stored.identity().assertedAt());
        var revoked=reopened.assetIdentities().change(tenant,entity,namespace,revoke(command.identityId(),2),now.plusNanos(1));assertFalse(revoked.identity().active());
        assertTrue(reopened.assetIdentities().resolve(tenant,namespace,value).isEmpty());
        assertTrue(reopened.assetIdentities().change(tenant,entity,namespace,command,now).identity().active());assertEquals(3,wiring.query().find(tenant,entity).orElseThrow().version());
        assertEquals(1,reopened.assetIdentities().identities(tenant,entity,namespace,null,25).size());
    }
    @Test void competingEntitiesForOneKeyHaveExactlyOneWinnerAndLoserRollsBack() throws Exception {
        var a=seed("1");var b=seed("2");
        try(var pool=Executors.newFixedThreadPool(2)){
            var calls=List.of(a,b).stream().map(entity->pool.submit(()->{try{wiring.assetIdentities().change(tenant,entity,namespace,claim(1,value),now);return true;}catch(SourceReview.Conflict expected){return false;}})).toList();
            boolean first=calls.get(0).get(20,TimeUnit.SECONDS),second=calls.get(1).get(20,TimeUnit.SECONDS);assertNotEquals(first,second);
            var winner=first?a:b;var loser=first?b:a;assertEquals(winner,wiring.assetIdentities().resolve(tenant,namespace,value).orElseThrow().identity().entityId());
            assertEquals(2,wiring.query().find(tenant,winner).orElseThrow().version());assertEquals(1,wiring.query().find(tenant,loser).orElseThrow().version());assertTrue(wiring.assetIdentities().identities(tenant,loser,namespace,null,25).isEmpty());
        }
    }
    @Test void revocationInvalidatesPendingPinnedImportAndCannotBeUsedOnAnotherTarget() {
        var a=seed("1");var b=seed("2");var claimed=wiring.assetIdentities().change(tenant,a,namespace,claim(1,value),now);
        var review=wiring.sourceReviews().stage(tenant,a,input(claimed.identity().pin(),2),now);
        wiring.assetIdentities().change(tenant,a,namespace,revoke(claimed.identity().id(),2),now);
        assertThrows(SourceReview.Conflict.class,()->wiring.sourceReviews().decide(tenant,a,source,review.id(),decide(SourceReview.Action.ACCEPT,3,1),now));
        assertThrows(SourceReview.Conflict.class,()->wiring.sourceReviews().stage(tenant,a,input(claimed.identity().pin(),3),now));
        var reassigned=wiring.assetIdentities().change(tenant,b,namespace,claim(1,value),now);
        assertThrows(SourceReview.Conflict.class,()->wiring.sourceReviews().stage(tenant,a,input(reassigned.identity().pin(),3),now));
        assertEquals("primary owner",wiring.query().find(tenant,a).orElseThrow().attributes().get("owner"));assertEquals(claimed.identity().pin(),openInventory(properties).sourceReviews().reviews(tenant,a,source,null,25).items().getFirst().identity());
    }
    @Test void activeFieldDependencyRequiresExplicitRollbackBeforeIdentityRevocation() {
        var entity=seed("1");var claimed=wiring.assetIdentities().change(tenant,entity,namespace,claim(1,value),now);
        var review=wiring.sourceReviews().stage(tenant,entity,input(claimed.identity().pin(),2),now);
        wiring.sourceReviews().decide(tenant,entity,source,review.id(),decide(SourceReview.Action.ACCEPT,2,1),now);
        assertThrows(SourceReview.Conflict.class,()->wiring.assetIdentities().change(tenant,entity,namespace,revoke(claimed.identity().id(),3),now));
        assertEquals(3,wiring.query().find(tenant,entity).orElseThrow().version());assertTrue(wiring.assetIdentities().resolve(tenant,namespace,value).isPresent());
        wiring.sourceReviews().decide(tenant,entity,source,review.id(),decide(SourceReview.Action.REVOKE,3,2),now);
        wiring.assetIdentities().change(tenant,entity,namespace,revoke(claimed.identity().id(),4),now);
        assertEquals("primary owner",wiring.query().find(tenant,entity).orElseThrow().attributes().get("owner"));assertEquals(5,wiring.query().find(tenant,entity).orElseThrow().version());
    }
    @Test void scopedReadsLimitsAndActorBoundRequestConflictsDoNotMutate() {
        var entity=seed("1");var cmd=claim(1,value);wiring.assetIdentities().change(tenant,entity,namespace,cmd,now);
        assertTrue(wiring.assetIdentities().resolve(new TenantId("other"),namespace,value).isEmpty());assertTrue(wiring.assetIdentities().resolve(tenant,"other",value).isEmpty());
        assertThrows(SourceReview.Conflict.class,()->wiring.assetIdentities().change(tenant,entity,namespace,new AssetIdentity.Command(cmd.requestId(),cmd.action(),cmd.identityId(),cmd.expectedEntityVersion(),value,"other-actor",cmd.reason()),now));
        for(int i=1;i<16;i++)wiring.assetIdentities().change(tenant,entity,namespace,claim(i+1,UUID.randomUUID().toString()),now);
        assertThrows(IllegalStateException.class,()->wiring.assetIdentities().change(tenant,entity,namespace,claim(17,UUID.randomUUID().toString()),now));
        assertEquals(17,wiring.query().find(tenant,entity).orElseThrow().version());var page=wiring.assetIdentities().identities(tenant,entity,namespace,null,1);assertEquals(2,page.size());
        assertEquals(page.get(1),wiring.assetIdentities().identities(tenant,entity,namespace,page.getFirst().id(),1).getFirst());
    }
}
