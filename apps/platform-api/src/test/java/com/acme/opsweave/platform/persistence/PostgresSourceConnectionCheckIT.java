package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.domain.SourceConnectionCheck;
import com.acme.opsweave.integration.api.SourceConnectionCheckStore;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresSourceConnectionCheckIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("connection-check-pg-"+UUID.randomUUID());
    final OpsweaveProperties properties=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),
        new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);
    final Instant base=Instant.parse("2026-09-26T06:00:00Z");

    SourceConnectionCheck check(long offsetSeconds,String statusCode,boolean reachable,String version){
        return new SourceConnectionCheck(UUID.randomUUID(),tenant,"zabbix-1","operator",base.plusSeconds(offsetSeconds),"labeled-fixture",reachable,statusCode,version);
    }

    @Test void receiptsRoundTripNewestFirstAndStayInScope(){
        var first=check(1,"labeled-fixture",true,null);
        var second=check(2,"ok",true,"7.0.4");
        var third=check(3,"unreachable",false,null);
        wiring.sourceChecks().record(first);wiring.sourceChecks().record(second);wiring.sourceChecks().record(third);
        var recent=wiring.sourceChecks().recent(tenant,"zabbix-1",10);
        assertEquals(List.of(third.id(),second.id(),first.id()),recent.stream().map(SourceConnectionCheck::id).toList());
        assertEquals("7.0.4",recent.get(1).reportedVersion());
        assertNull(recent.getFirst().reportedVersion());
        assertEquals(3,wiring.sourceChecks().kept(tenant,"zabbix-1"));
        assertTrue(wiring.sourceChecks().recent(new TenantId("other-"+UUID.randomUUID()),"zabbix-1",10).isEmpty());
        assertTrue(wiring.sourceChecks().recent(tenant,"zabbix-2",10).isEmpty());
        assertEquals(1,wiring.sourceChecks().recent(tenant,"zabbix-1",1).size());
        assertEquals(3,wiring.sourceChecks().recent(tenant,"zabbix-1",SourceConnectionCheckStore.MAX_RECENT).size());
        assertThrows(IllegalArgumentException.class,()->wiring.sourceChecks().recent(tenant,"zabbix-1",0));
        assertThrows(IllegalArgumentException.class,()->wiring.sourceChecks().recent(tenant,"zabbix-1",51));
    }

    @Test void onlyTheNewestReceiptsAreKept(){
        for(int i=0;i<SourceConnectionCheck.MAX_KEPT+2;i++){
            wiring.sourceChecks().record(check(i,"labeled-fixture",true,null));
        }
        assertEquals(SourceConnectionCheck.MAX_KEPT,wiring.sourceChecks().kept(tenant,"zabbix-1"));
        var newest=wiring.sourceChecks().recent(tenant,"zabbix-1",50);
        assertEquals(50,newest.size());
        // Newest offset is MAX_KEPT+1; a page of 50 ends 49 seconds earlier.
        assertEquals(base.plusSeconds(SourceConnectionCheck.MAX_KEPT+1),newest.getFirst().checkedAt());
        assertEquals(base.plusSeconds(SourceConnectionCheck.MAX_KEPT+1-49),newest.getLast().checkedAt());
    }
}
