package com.acme.opsweave.platform.inventory;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.infrastructure.InMemoryRelationStore;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityRelationStoreTest {
    @Test void requestKeyIsIdempotentAndActivePageIsBounded() {
        var tenant=new TenantId("relation-test");var from=new EntityId(UUID.randomUUID());var to=new EntityId(UUID.randomUUID());var request=UUID.randomUUID();
        var relation=new EntityRelation(request,tenant,from,"builtin.depends_on",1,to,Instant.parse("2026-10-01T00:00:00Z"),null,"operator","unknown",1);
        var store=new InMemoryRelationStore();var first=store.write(tenant,request,relation,1,1);var replay=store.write(tenant,request,relation,1,1);
        assertFalse(first.replayed());assertTrue(replay.replayed());assertEquals(1,store.page(tenant,from,null,Instant.parse("2026-10-02T00:00:00Z"),25).size());
        assertTrue(store.page(tenant,from,null,Instant.parse("2026-09-30T00:00:00Z"),25).isEmpty());
        var changed=new EntityRelation(request,tenant,from,"builtin.depends_on",1,to,relation.validFrom(),null,"changed","unknown",1);
        assertThrows(com.acme.opsweave.inventory.api.RelationStore.Conflict.class,()->store.write(tenant,request,changed,1,1));
    }
}
