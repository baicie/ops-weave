package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.RejectedWriteAttempt;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The refusal log is bounded and stores only field names. A refused write leaves no receipt, so this
 * table is the only evidence that an operator tried and was refused.
 */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresRejectedWriteAttemptIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("rejected-write-pg-"+UUID.randomUUID());
    final String source="cmdb-import";
    final OpsweaveProperties properties=new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),
        new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),
        new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties);

    DriverManagerDataSource dataSource(){
        return new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    }

    RejectedWriteAttempt attempt(String code,String method,String actor,List<String> fields,Instant at){
        return new RejectedWriteAttempt(UUID.randomUUID(),tenant,source,
            RejectedWriteAttempt.Kind.valueOf(method.equals("CORRECT_BINDING")||method.equals("INGEST_SNAPSHOT")?"BINDING_CORRECTION":"FIELD_REVIEW"),
            RejectedWriteAttempt.Method.valueOf(method),RejectedWriteAttempt.Code.valueOf(code),actor,fields,at);
    }

    long rowsInScope()throws Exception{
        try(var db=DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
            var s=db.prepareStatement("SELECT count(*) FROM integration.rejected_write_attempt WHERE tenant_id=? AND source_instance_id=?")){
            s.setString(1,tenant.value());s.setString(2,source);
            try(var r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }

    @Test void aRecordedRefusalRoundTripsWithItsFieldNamesAndStableCode()throws Exception{
        var store=new PostgresRejectedWriteAttempts(dataSource(),RejectedWriteAttempt.Policy.defaults());
        var at=Instant.parse("2026-09-26T09:41:12.482913Z");
        store.record(attempt("BINDING_FIELDS_ACTIVE","CORRECT_BINDING","operator",List.of("owner","environment"),at));
        var stored=store.recent(tenant,source,10).getFirst();
        assertEquals("BINDING_FIELDS_ACTIVE",stored.code().name());
        assertEquals("Active fields must be revoked before they can be corrected.",stored.code().summary(),
            "the stored code keeps its fixed summary");
        assertEquals(RejectedWriteAttempt.Method.CORRECT_BINDING,stored.method());
        assertEquals(RejectedWriteAttempt.Kind.BINDING_CORRECTION,stored.kind());
        assertEquals("operator",stored.actor());
        assertEquals(List.of("owner","environment"),stored.fieldNames());
        assertEquals(at,stored.attemptedAt());
        assertEquals(1,store.kept(tenant,source));
        assertEquals(1L,rowsInScope());
    }

    @Test void theScopeNeverHoldsMoreThanItsBudgetAndReadsPruneNothing()throws Exception{
        var store=new PostgresRejectedWriteAttempts(dataSource(),new RejectedWriteAttempt.Policy(3));
        for(int index=0;index<6;index++){
            store.record(attempt("BINDING_CHANGED","CORRECT_BINDING","operator",List.of("ip"),
                Instant.parse("2026-09-26T09:00:00Z").plusSeconds(index)));
        }
        assertEquals(3,store.kept(tenant,source),"the scope never holds more than its budget");
        assertEquals(3L,rowsInScope(),"the budget bounds what is really stored");
        assertEquals(3,store.recent(tenant,source,10).size());
        assertEquals(Instant.parse("2026-09-26T09:00:05Z"),store.recent(tenant,source,10).getFirst().attemptedAt(),
            "the newest refusal is first");
        assertEquals(Instant.parse("2026-09-26T09:00:03Z"),store.recent(tenant,source,10).getLast().attemptedAt(),
            "the oldest refusals were pruned");
        assertEquals(3,store.recent(tenant,source,10).size(),"reading prunes nothing");
    }

    @Test void scopesAndTenantsAreIsolated()throws Exception{
        var store=new PostgresRejectedWriteAttempts(dataSource(),new RejectedWriteAttempt.Policy(2));
        var at=Instant.parse("2026-09-26T09:00:00Z");
        store.record(attempt("BINDING_CHANGED","CORRECT_BINDING","operator",List.of("ip"),at));
        store.record(new RejectedWriteAttempt(UUID.randomUUID(),tenant,"cmdb-other",RejectedWriteAttempt.Kind.FIELD_REVIEW,
            RejectedWriteAttempt.Method.STAGE_REVIEW,RejectedWriteAttempt.Code.ENTITY_VERSION_CHANGED,"operator",List.of("name"),at));
        var other=new TenantId("rejected-write-pg-other-"+UUID.randomUUID());
        store.record(new RejectedWriteAttempt(UUID.randomUUID(),other,source,RejectedWriteAttempt.Kind.FIELD_REVIEW,
            RejectedWriteAttempt.Method.STAGE_REVIEW,RejectedWriteAttempt.Code.ENTITY_VERSION_CHANGED,"operator",List.of("name"),at));
        assertEquals(1,store.kept(tenant,source));
        assertEquals(1,store.kept(tenant,"cmdb-other"));
        assertEquals(1,store.kept(other,source));
        assertEquals("cmdb-other",store.recent(tenant,"cmdb-other",10).getFirst().sourceInstanceId());
        assertEquals(1,store.recent(tenant,source,10).size(),"another scope's rows never leak into this one");
    }

    @Test void theReadingTraceKeepsOnlyAllowListedFieldNamesAndNeverAValue()throws Exception{
        var store=new PostgresRejectedWriteAttempts(dataSource(),RejectedWriteAttempt.Policy.defaults());
        store.record(attempt("REVIEW_STATE_CHANGED","DECIDE_REVIEW","unknown",List.of("ip"),Instant.parse("2026-09-26T09:00:00Z")));
        var stored=store.recent(tenant,source,10).getFirst();
        assertEquals(List.of("ip"),stored.fieldNames());
        assertFalse(stored.toString().contains("10.0.0"),"no value is stored or returned");
        assertEquals("unknown",stored.actor());
    }
}
