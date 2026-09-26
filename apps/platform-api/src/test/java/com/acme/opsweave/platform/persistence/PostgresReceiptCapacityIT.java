package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.inventory.domain.SourceReceiptCapacity;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The receipt caps fail closed, so the capacity view has to report the same counters the write path
 * enforces. It counts what is really stored and removes nothing.
 */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresReceiptCapacityIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("receipt-capacity-pg-"+UUID.randomUUID());
    final String source="cmdb-import";
    final OpsweaveProperties properties=new OpsweaveProperties(
        new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),
        new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),
        new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD")));
    final InventoryWiring wiring=openInventory(properties,"cmdb-import");

    DriverManagerDataSource dataSource(){
        return new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    }

    /** Seeds counted rows directly; the receipt body is irrelevant to counting and the write path is covered elsewhere. */
    void seedSnapshots(int rows)throws Exception{
        try(var db=DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
            var s=db.prepareStatement("INSERT INTO inventory.source_snapshot (tenant_id,source_instance_id,request_id,actor,namespace,observed_at,observed_epoch_nanos,body) SELECT ?,?,gen_random_uuid(),'operator','assets',now(),?, '{}'::jsonb FROM generate_series(1,?)")){
            s.setString(1,tenant.value());s.setString(2,source);
            s.setLong(3,Instant.now().minusSeconds(60).getEpochSecond()*1000000000L);
            s.setInt(4,rows);
            s.executeUpdate();
        }
    }

    @Test void anEmptySourceReportsZeroAndStaysOk()throws Exception{
        var capacity=wiring.receiptCapacity();
        assertEquals(0,capacity.snapshotReceipts(tenant,source));
        assertEquals(0,capacity.correctionReceipts(tenant,source));
        var reader=new com.acme.opsweave.inventory.application.SourceReceiptCapacityService(
            new com.acme.opsweave.identity.application.AuthorizeUseCase(),capacity,source);
        var rows=reader.capacity(new com.acme.opsweave.identity.domain.Principal(new com.acme.opsweave.identity.domain.SubjectId("operator"),
            tenant,java.util.Set.of(com.acme.opsweave.identity.domain.Permission.ENTITY_READ,com.acme.opsweave.identity.domain.Permission.ENTITY_MANAGE,com.acme.opsweave.identity.domain.Permission.SOURCE_SYNC),
            com.acme.opsweave.identity.domain.ResourceScope.tenantWide()));
        assertEquals(2,rows.size());
        assertTrue(rows.stream().allMatch(row->row.status()==SourceReceiptCapacity.Status.OK&&row.kept()==0));
    }

    @Test void theReportedCountIsWhatIsReallyStoredAndCountingPrunesNothing()throws Exception{
        seedSnapshots(SourceReceiptCapacity.cap(SourceReceiptCapacity.Kind.SNAPSHOT));
        var capacity=wiring.receiptCapacity();
        assertEquals(1000,capacity.snapshotReceipts(tenant,source));
        assertEquals(1000,capacity.snapshotReceipts(tenant,source),"counting twice changes nothing");
        assertEquals(1000L,stored(),"the view never removed a stored receipt");
        assertEquals(SourceReceiptCapacity.Status.AT_LIMIT,new SourceReceiptCapacity(SourceReceiptCapacity.Kind.SNAPSHOT,source,
            capacity.snapshotReceipts(tenant,source),SourceReceiptCapacity.cap(SourceReceiptCapacity.Kind.SNAPSHOT)).status(),
            "the published cap is reported as reached");
        assertEquals(0,capacity.correctionReceipts(tenant,source),"the other cap is counted separately");
    }

    @Test void anotherTenantOrSourceIsNeverCounted()throws Exception{
        seedSnapshots(3);
        var capacity=wiring.receiptCapacity();
        assertEquals(3,capacity.snapshotReceipts(tenant,source));
        assertEquals(0,capacity.snapshotReceipts(new TenantId("receipt-capacity-other-"+UUID.randomUUID()),source));
        assertEquals(0,capacity.snapshotReceipts(tenant,"cmdb-other"));
        assertEquals(3L,stored());
    }

    long stored()throws Exception{
        try(var db=DriverManager.getConnection(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
            var s=db.prepareStatement("SELECT count(*) FROM inventory.source_snapshot WHERE tenant_id=? AND source_instance_id=?")){
            s.setString(1,tenant.value());s.setString(2,source);
            try(var r=s.executeQuery()){r.next();return r.getLong(1);}
        }
    }
}
