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
}
