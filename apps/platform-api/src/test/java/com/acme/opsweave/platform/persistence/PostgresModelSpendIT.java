package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.platform.OpsweaveProperties;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresModelSpendIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("model-spend-pg-"+UUID.randomUUID());final SubjectId user=new SubjectId("operator");
    final Instant now=Instant.parse("2026-09-25T23:59:50.123456789Z");
    final ModelSpend.Policy policy=new ModelSpend.Policy("rig-openai","fixture-model","fixture-rates",2_000_000,10_000_000,184_320,200_000);
    final OpsweaveProperties properties=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);
    ModelSpend.Call call(Instant at){return new ModelSpend.Call(tenant,user,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"sha256:"+"b".repeat(64),1234,policy,at,at.plusSeconds(40),null,null);}
    @Test void tenantLockPreventsConcurrentOverspendAcrossConnections()throws Exception{
        try(var pool=Executors.newFixedThreadPool(4)){
            var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{try{wiring.modelSpend().reserve(call(now));return true;}catch(ToolFailure e){assertEquals(ToolFailure.Code.BUDGET_EXHAUSTED,e.code());return false;}}));
            int count=0;for(var future:futures)if(future.get(20,TimeUnit.SECONDS))count++;assertEquals(1,count);
        }
    }
    @Test void reopenAndUtcRolloverRetainUnknownChargeAndBlockDuplicateInvocation(){
        var a=call(now);wiring.modelSpend().reserve(a);var reopened=openInventory(properties).modelSpend();
        assertEquals(a,reopened.find(tenant,a.runId()).orElseThrow());
        assertEquals(ToolFailure.Code.INPUT_CHANGED,assertThrows(ToolFailure.class,()->reopened.reserve(a)).code());
        assertEquals(ToolFailure.Code.BUDGET_EXHAUSTED,assertThrows(ToolFailure.class,()->reopened.reserve(call(now.plusSeconds(86400)))).code());
        assertEquals("UNCERTAIN",reopened.find(tenant,a.runId()).orElseThrow().state(now.plusSeconds(86400)));
    }
    @Test void reportIsActorBoundImmutableAndReleasesOnlyUnusedEstimate(){
        var a=call(now);var store=wiring.modelSpend();store.reserve(a);var usage=new ModelSpend.Usage(100,20,50,"provider-reported");
        assertThrows(ToolFailure.class,()->store.report(tenant,new SubjectId("other"),a.runId(),a.sessionId(),usage,now.plusSeconds(1)));
        assertNull(store.find(tenant,a.runId()).orElseThrow().usage());
        var reported=store.report(tenant,user,a.runId(),a.sessionId(),usage,now.plusSeconds(1));assertEquals(400,reported.chargedMicros());
        assertEquals(reported,store.report(tenant,user,a.runId(),a.sessionId(),usage,now.plusSeconds(2)));
        assertThrows(ToolFailure.class,()->store.report(tenant,user,a.runId(),a.sessionId(),new ModelSpend.Usage(101,20,50,"provider-reported"),now.plusSeconds(2)));
        assertEquals(reported,openInventory(properties).modelSpend().find(tenant,a.runId()).orElseThrow());assertTrue(store.find(new TenantId("other"),a.runId()).isEmpty());
        assertNotNull(store.reserve(call(now)));assertEquals(reported.policy(),policy);
    }
}
