package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresEntityInstanceIT extends OwnedInventoryTest {
    private final TenantId tenant=new TenantId("entity-instance-pg-"+UUID.randomUUID());
    private final InventoryWiring wiring=openInventory(new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),
        new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","entity-instance-fixture",1),
        new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),
            System.getenv().getOrDefault("OPSWEAVE_TEST_JDBC_USER","opsweave_dev"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));

    @Test void concurrentIdenticalCreateReturnsOneWriteAndTheSamePinnedReceipt() throws Exception {
        var requestId=UUID.randomUUID();var id=new EntityId(UUID.randomUUID());var now=Instant.parse("2026-10-06T00:00:00Z");
        var entity=new Entity(id,tenant,"Application","Concurrent",Lifecycle.ACTIVE,1,now,Map.of("name","Concurrent"));
        var pin=new EntityModelPin("builtin.application",1,"sha256:"+"a".repeat(64));
        var results=new ArrayList<com.acme.opsweave.inventory.api.EntityInstanceStore.WriteResult>();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<com.acme.opsweave.inventory.api.EntityInstanceStore.WriteResult>>();
            for(int i=0;i<6;i++)jobs.add(workers.submit(()->{gate.await();return wiring.entityInstances().write(tenant,requestId,entity,null,pin.id(),pin.revision(),pin.digest());}));
            gate.countDown();for(var job:jobs)results.add(job.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,results.stream().filter(result->!result.replayed()).count());
        assertTrue(results.stream().allMatch(result->result.entity().model().equals(pin)));
        assertEquals(pin,wiring.query().find(tenant,id).orElseThrow().model());
    }

    @Test void requestReplayKeepsModelPinAndCasRejectsStaleUpdate() {
        var requestId = UUID.randomUUID();
        var id = new EntityId(UUID.randomUUID());
        var createdAt = Instant.parse("2026-10-06T00:00:00Z");
        var pin = new EntityModelPin("builtin.application", 1, "sha256:" + "b".repeat(64));
        var entity = new Entity(id, tenant, "Application", "Pinned application", Lifecycle.ACTIVE, 1,
            createdAt, Map.of("name", "Pinned application"));

        var first = wiring.entityInstances().write(tenant, requestId, entity, null,
            pin.id(), pin.revision(), pin.digest());
        assertFalse(first.replayed());
        assertEquals(pin, first.entity().model());

        var replay = wiring.entityInstances().write(tenant, requestId, entity, null,
            pin.id(), pin.revision(), pin.digest());
        assertTrue(replay.replayed());
        assertEquals(first.entity(), replay.entity());

        var otherPin = new EntityModelPin("builtin.service", 1, "sha256:" + "c".repeat(64));
        assertThrows(com.acme.opsweave.inventory.api.EntityInstanceStore.Conflict.class,
            () -> wiring.entityInstances().write(tenant, requestId, entity, null,
                otherPin.id(), otherPin.revision(), otherPin.digest()));

        var updated = new Entity(id, tenant, "Application", "Pinned application v2", Lifecycle.ACTIVE, 2,
            createdAt.plusSeconds(1), Map.of("name", "Pinned application v2"));
        var update = wiring.entityInstances().write(tenant, UUID.randomUUID(), updated, Long.valueOf(1),
            pin.id(), pin.revision(), pin.digest());
        assertFalse(update.replayed());
        assertEquals(2, update.entity().version());
        assertEquals(pin, wiring.query().find(tenant, id).orElseThrow().model());

        var stale = new Entity(id, tenant, "Application", "stale", Lifecycle.ACTIVE, 2,
            createdAt.plusSeconds(2), Map.of("name", "stale"));
        assertThrows(com.acme.opsweave.inventory.api.EntityInstanceStore.Conflict.class,
            () -> wiring.entityInstances().write(tenant, UUID.randomUUID(), stale, Long.valueOf(1),
                pin.id(), pin.revision(), pin.digest()));
        assertEquals("Pinned application v2", wiring.query().find(tenant, id).orElseThrow().name());
    }
}
