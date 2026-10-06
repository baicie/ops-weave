package com.acme.opsweave.platform.inventory;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class EntityInstanceStoreTest {
    @Test void requestIsIdempotentAndVersionIsChecked() {
        var tenant=new TenantId("entity-instance-test");var id=new EntityId(UUID.randomUUID());var request=UUID.randomUUID();var now=Instant.parse("2026-10-06T00:00:00Z");
        var store=new InMemoryInventoryStore();var first=new Entity(id,tenant,"Application","Orders",Lifecycle.ACTIVE,1,now,Map.of("name","Orders"));
        assertFalse(store.write(tenant,request,first,null).replayed());
        var retry=new Entity(id,tenant,"Application","Orders",Lifecycle.ACTIVE,1,now.plusSeconds(10),Map.of("name","Orders"));
        var replay=store.write(tenant,request,retry,null);
        assertTrue(replay.replayed());assertEquals(first.lastSeen(),replay.entity().lastSeen());
        var changed=new Entity(id,tenant,"Application","Changed",Lifecycle.ACTIVE,1,now.plusSeconds(10),Map.of("name","Orders"));
        assertThrows(com.acme.opsweave.inventory.api.EntityInstanceStore.Conflict.class,()->store.write(tenant,request,changed,null));
        assertThrows(com.acme.opsweave.inventory.api.EntityInstanceStore.Conflict.class,()->store.write(tenant,request,first,null,"builtin.application",1,"sha256:"+"a".repeat(64)));
        var pinnedRequest=UUID.randomUUID();
        var pinnedEntity=new Entity(new EntityId(UUID.randomUUID()),tenant,"Application","Pinned",Lifecycle.ACTIVE,1,now,Map.of("name","Pinned"));
        var pinnedRetry=new Entity(pinnedEntity.id(),tenant,"Application","Pinned",Lifecycle.ACTIVE,1,now.plusSeconds(10),Map.of("name","Pinned"));
        var pin=new EntityModelPin("builtin.application",1,"sha256:"+"a".repeat(64));
        var pinnedWrite=store.write(tenant,pinnedRequest,pinnedEntity,null,pin.id(),pin.revision(),pin.digest());
        assertFalse(pinnedWrite.replayed()); assertEquals(pin,pinnedWrite.entity().model());
        assertEquals(pin,store.find(tenant,pinnedEntity.id()).orElseThrow().model());
        assertEquals(pin,store.page(tenant,new EntityVisibility(true,Set.of()),new EntityPageQuery("",null,"",null,25))
            .stream().filter(view->view.id().equals(pinnedEntity.id())).findFirst().orElseThrow().model());
        assertTrue(store.write(tenant,pinnedRequest,pinnedRetry,null,"builtin.application",1,"sha256:"+"a".repeat(64)).replayed());
        assertThrows(com.acme.opsweave.inventory.api.EntityInstanceStore.Conflict.class,()->store.write(tenant,pinnedRequest,pinnedRetry,null,"builtin.application",2,"sha256:"+"b".repeat(64)));
        assertThrows(com.acme.opsweave.inventory.api.EntityInstanceStore.Conflict.class,()->store.write(tenant,pinnedRequest,pinnedRetry,1L,"builtin.application",1,"sha256:"+"a".repeat(64)));
        var next=new Entity(id,tenant,"Application","Orders v2",Lifecycle.ACTIVE,2,now.plusSeconds(1),Map.of("name","Orders v2"));
        assertFalse(store.write(tenant,UUID.randomUUID(),next,1L).replayed());assertEquals(2,store.find(tenant,id).orElseThrow().version());
        assertThrows(com.acme.opsweave.inventory.api.EntityInstanceStore.Conflict.class,()->store.write(tenant,UUID.randomUUID(),next,1L));
        var nullable=new LinkedHashMap<String,Object>(); nullable.put("description",null);
        var nullableEntity=new Entity(new EntityId(UUID.randomUUID()),tenant,"Application","Nullable",Lifecycle.ACTIVE,1,now,nullable);
        assertNull(store.write(tenant,UUID.randomUUID(),nullableEntity,null).entity().attributes().get("description"));

        var sourceKey=new ExternalObjectKey(tenant,"source-1","application","pinned-entity","generation-1");
        var sourceAt=now.plusSeconds(30); var sourceAttrs=Map.<String,Object>of("name","Published source");
        store.upsert(new Entity(pinnedEntity.id(),tenant,"Application","Published source",Lifecycle.ACTIVE,1,sourceAt,sourceAttrs),
            new Observation("source-update",sourceKey,pinnedEntity.id(),sourceAt,sourceAt,sourceAttrs,"raw:source-update",1),new ExternalLink(pinnedEntity.id(),sourceKey));
        assertNull(store.find(tenant,pinnedEntity.id()).orElseThrow().model());
        assertNull(store.page(tenant,new EntityVisibility(true,Set.of()),new EntityPageQuery("",null,"",null,25))
            .stream().filter(view->view.id().equals(pinnedEntity.id())).findFirst().orElseThrow().model());
    }
}
