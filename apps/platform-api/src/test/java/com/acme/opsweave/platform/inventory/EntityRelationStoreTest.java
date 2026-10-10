package com.acme.opsweave.platform.inventory;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.domain.RelationCardinality;
import com.acme.opsweave.inventory.infrastructure.InMemoryRelationStore;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityRelationStoreTest {
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private final TenantId tenant = new TenantId("relation-test");

    @Test void requestKeyIsIdempotentAndActivePageIsBounded() {
        var from=new EntityId(UUID.randomUUID());var to=new EntityId(UUID.randomUUID());var request=UUID.randomUUID();
        var relation=new EntityRelation(request,tenant,from,"builtin.depends_on",1,to,START,null,"operator","unknown",1);
        var store=new InMemoryRelationStore();
        var first=store.write(tenant,request,relation,1,1, RelationCardinality.MANY_TO_MANY);var replay=store.write(tenant,request,relation,1,1, RelationCardinality.MANY_TO_MANY);
        assertFalse(first.replayed());assertTrue(replay.replayed());assertEquals(1,store.page(tenant,from,null,Instant.parse("2026-10-02T00:00:00Z"),25).size());
        assertTrue(store.page(tenant,from,null,Instant.parse("2026-09-30T00:00:00Z"),25).isEmpty());
        var changed=new EntityRelation(request,tenant,from,"builtin.depends_on",1,to,relation.validFrom(),null,"changed","unknown",1);
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.Conflict.class,()->store.write(tenant,request,changed,1,1, RelationCardinality.MANY_TO_MANY));
        var duplicate=UUID.randomUUID();
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.Conflict.class,()->
            store.write(tenant,duplicate,new EntityRelation(duplicate,tenant,from,"builtin.depends_on",1,to,START.plusSeconds(3),null,"second-source","unknown",1),1,1, RelationCardinality.MANY_TO_MANY));
    }

    @Test void closeIsIdempotentAndKeepsHistory() {
        var from=new EntityId(UUID.randomUUID());var to=new EntityId(UUID.randomUUID());var request=UUID.randomUUID();
        var relation=new EntityRelation(request,tenant,from,"builtin.depends_on",1,to,START,null,"operator","unknown",1);
        var store=new InMemoryRelationStore();
        store.write(tenant,request,relation,1,1, RelationCardinality.MANY_TO_MANY);
        var closeRequest=UUID.randomUUID();var end=START.plusSeconds(30);
        var first=store.close(tenant,closeRequest,request,end,"业务确认解绑",1);
        assertFalse(first.replayed());assertEquals(2,first.relation().version());assertEquals("业务确认解绑",first.relation().closeReason());
        var replay=store.close(tenant,closeRequest,request,end,"业务确认解绑",1);
        assertTrue(replay.replayed());assertEquals(first.relation(),replay.relation());
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.Conflict.class,()->store.close(tenant,closeRequest,request,START.plusSeconds(60),"改动后的理由",1));
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.Conflict.class,()->store.close(tenant,UUID.randomUUID(),request,end,"重复终止",2));
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.NotFound.class,()->store.close(tenant,UUID.randomUUID(),UUID.randomUUID(),end,"不存在",1));
        assertTrue(store.page(tenant,from,null,START.plusSeconds(60),25).isEmpty());
        assertEquals(1,store.history(tenant,from,null,25).size());
        var createReplay=store.write(tenant,request,relation,1,1, RelationCardinality.MANY_TO_MANY);
        assertTrue(createReplay.replayed());assertEquals(2,createReplay.relation().version());assertEquals("业务确认解绑",createReplay.relation().closeReason());
        var resurrect=UUID.randomUUID();
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.Conflict.class,()->
            store.write(tenant,resurrect,new EntityRelation(resurrect,tenant,from,"builtin.depends_on",1,to,START.plusSeconds(5),null,"second-source","unknown",1),1,1, RelationCardinality.MANY_TO_MANY));
    }

    @Test void cardinalityCountsOnlyOpenEndedRelations() {
        var hub=new EntityId(UUID.randomUUID());var a=new EntityId(UUID.randomUUID());var b=new EntityId(UUID.randomUUID());
        var store=new InMemoryRelationStore();
        var first=UUID.randomUUID();var second=UUID.randomUUID();
        store.write(tenant,first,new EntityRelation(first,tenant,hub,"builtin.has_interface",1,a,START,null,"operator","unknown",1),1,1, RelationCardinality.ONE_TO_MANY);
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.Conflict.class,()->
            store.write(tenant,second,new EntityRelation(second,tenant,hub,"builtin.has_interface",1,b,START,null,"operator","unknown",1),1,1, RelationCardinality.ONE_TO_MANY));
        store.close(tenant,UUID.randomUUID(),first,START.plusSeconds(10),"接口回收",1);
        store.write(tenant,second,new EntityRelation(second,tenant,hub,"builtin.has_interface",1,b,START,null,"operator","unknown",1),1,1, RelationCardinality.ONE_TO_MANY);
        assertEquals(2,store.history(tenant,hub,null,25).size());
    }

    @Test void correctionsKeepThePreviousRecordAndStayIdempotent() {
        var from=new EntityId(UUID.randomUUID());var to=new EntityId(UUID.randomUUID());var other=new EntityId(UUID.randomUUID());
        var store=new InMemoryRelationStore();
        var request=UUID.randomUUID();
        var relation=new EntityRelation(request,tenant,from,"builtin.has_interface",1,to,START,null,"operator","unknown",1);
        store.write(tenant,request,relation,1,1,RelationCardinality.ONE_TO_MANY);
        var correctionRequest=UUID.randomUUID();
        var replacement=new EntityRelation(UUID.randomUUID(),tenant,from,"builtin.has_interface",1,other,START.plusSeconds(30),null,"operator","unknown",1);
        var corrected=store.correct(tenant,correctionRequest,request,"端点录入错误",1,replacement,RelationCardinality.ONE_TO_MANY);
        assertFalse(corrected.replayed());
        var kept=store.find(tenant,request).orElseThrow();
        assertEquals(START.plusSeconds(30),kept.validTo());assertEquals("端点录入错误",kept.closeReason());assertEquals(2,kept.version());
        assertEquals(other,store.find(tenant,corrected.relation().id()).orElseThrow().toEntityId());
        var replay=store.correct(tenant,correctionRequest,request,"端点录入错误",1,new EntityRelation(UUID.randomUUID(),tenant,from,"builtin.has_interface",1,other,START.plusSeconds(60),null,"operator","unknown",1),RelationCardinality.ONE_TO_MANY);
        assertTrue(replay.replayed());assertEquals(corrected.relation().id(),replay.relation().id());
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.CorrectionConflict.class,() ->
            store.correct(tenant,correctionRequest,request,"改动后的理由",1,replacement,RelationCardinality.ONE_TO_MANY));
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.CorrectionConflict.class,() ->
            store.correct(tenant,UUID.randomUUID(),request,"终止后不复活",1,new EntityRelation(UUID.randomUUID(),tenant,from,"builtin.has_interface",1,to,START.plusSeconds(40),null,"operator","unknown",1),RelationCardinality.ONE_TO_MANY));
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.Conflict.class,() ->
            store.write(tenant,UUID.randomUUID(),new EntityRelation(UUID.randomUUID(),tenant,from,"builtin.has_interface",1,to,START.plusSeconds(40),null,"operator","unknown",1),1,1,RelationCardinality.ONE_TO_MANY));
        var records=store.corrections(tenant,from,null,25);
        assertEquals(1,records.size());assertEquals(request,records.getFirst().previousRelationId());
        assertTrue(store.corrections(tenant,other,null,25).stream().anyMatch(r -> r.relationId().equals(corrected.relation().id())));
    }

    @Test void confirmationsMergeSourcesAndWithdrawInIsolation() {
        var from=new EntityId(UUID.randomUUID());var to=new EntityId(UUID.randomUUID());
        var store=new InMemoryRelationStore();
        var request=UUID.randomUUID();
        var relation=new EntityRelation(request,tenant,from,"builtin.depends_on",1,to,START,null,"operator","unknown",1);
        store.write(tenant,request,relation,1,1,RelationCardinality.MANY_TO_MANY);
        var first=store.confirm(tenant,UUID.randomUUID(),request,new com.acme.opsweave.inventory.domain.RelationConfirmation("source","workflow.a","batch-1","fixture",START.plusSeconds(1),null,null));
        store.confirm(tenant,UUID.randomUUID(),request,new com.acme.opsweave.inventory.domain.RelationConfirmation("source","workflow.b","batch-1","import",START.plusSeconds(1),null,null));
        var replay=store.confirm(tenant,UUID.randomUUID(),request,new com.acme.opsweave.inventory.domain.RelationConfirmation("source","workflow.a","batch-1","fixture",START.plusSeconds(1),null,null));
        assertTrue(replay.replayed());assertEquals(first.entry(),replay.entry());
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.ConfirmationConflict.class,() ->
            store.confirm(tenant,UUID.randomUUID(),request,new com.acme.opsweave.inventory.domain.RelationConfirmation("source","workflow.a","batch-1","unknown",START.plusSeconds(1),null,null)));
        var key=new com.acme.opsweave.inventory.domain.RelationConfirmation.Key("source","workflow.a","batch-1");
        var withdrawn=store.withdraw(tenant,UUID.randomUUID(),request,key,START.plusSeconds(2),"来源批次撤销");
        assertTrue(withdrawn.entry().withdrawn());
        assertTrue(store.find(tenant,request).orElseThrow().validTo()==null,"other source still confirms");
        var again=store.withdraw(tenant,UUID.randomUUID(),request,key,START.plusSeconds(3),"另一个理由");
        assertTrue(again.replayed());assertEquals("来源批次撤销",again.entry().withdrawReason());
        var last=store.withdraw(tenant,UUID.randomUUID(),request,new com.acme.opsweave.inventory.domain.RelationConfirmation.Key("source","workflow.b","batch-1"),START.plusSeconds(4),"来源下线");
        assertFalse(last.replayed());
        var terminated=store.find(tenant,request).orElseThrow();
        assertEquals(START.plusSeconds(4),terminated.validTo());assertEquals("来源下线",terminated.closeReason());
        assertEquals(2,terminated.version());
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.ConfirmationConflict.class,() ->
            store.confirm(tenant,UUID.randomUUID(),request,new com.acme.opsweave.inventory.domain.RelationConfirmation("manual","operator","assert-1","unknown",START.plusSeconds(5),null,null)));
        assertEquals(2,store.confirmations(tenant,request).size());
    }
}
