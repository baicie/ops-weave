package com.acme.opsweave.platform.persistence;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresTopologyIT extends OwnedInventoryTest {
 final TenantId tenant=new TenantId("topology-pg-"+UUID.randomUUID());
 final DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
 final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
 final Instant at=Instant.parse("2026-09-28T00:00:00Z");
 EntityId node()throws Exception{var id=new EntityId(UUID.randomUUID());try(var c=source.getConnection();var s=c.prepareStatement("INSERT INTO inventory.entity(tenant_id,id,entity_type,name,lifecycle,version,attributes,last_seen_at,last_seen_epoch_nanos) VALUES(?,?,'Host','Fixture topology','ACTIVE',1,'{\"dataMode\":\"labeled-fixture\"}',now(),1790553600000000000)")){s.setString(1,tenant.value());s.setObject(2,id.value());s.executeUpdate();}return id;}
 void edge(EntityId a,EntityId b,Instant from,Instant to)throws Exception{try(var c=source.getConnection();var s=c.prepareStatement("INSERT INTO inventory.entity_relation(tenant_id,id,from_entity_id,to_entity_id,relation_type,valid_from,valid_to,source_ref,data_mode) VALUES(?,?,?,?,'depends_on',?,?,'fixture:topology-only','fixture')")){s.setString(1,tenant.value());s.setObject(2,UUID.randomUUID());s.setObject(3,a.value());s.setObject(4,b.value());s.setTimestamp(5,java.sql.Timestamp.from(from));s.setTimestamp(6,to==null?null:java.sql.Timestamp.from(to));s.executeUpdate();}}
 @Test void authorizedNeighborhoodExcludesHiddenExpiredFutureAndOtherTenants()throws Exception{var a=node();var b=node();var hidden=node();edge(a,b,at.minusSeconds(1),null);for(int i=0;i<55;i++)edge(a,hidden,at.minusSeconds(1),null);edge(a,b,at.plusSeconds(1),null);edge(a,b,at.minusSeconds(5),at);var v=new PostgresTopologyReader(source).read(tenant,a,new EntityVisibility(false,Set.of(a,b)),at).orElseThrow();assertEquals(2,v.nodes().size());assertEquals(1,v.edges().size());assertFalse(v.truncated());assertTrue(v.nodes().stream().allMatch(n->n.dataMode().equals("fixture")));assertTrue(new PostgresTopologyReader(source).read(new TenantId("other"),a,new EntityVisibility(true,Set.of()),at).isEmpty());assertTrue(new PostgresTopologyReader(source).read(tenant,a,new EntityVisibility(false,Set.of(b)),at).isEmpty());}
 @Test void reopenedReaderHasBoundedExplicitTruncationAndNoInventedEdges()throws Exception{var a=node();var b=node();var empty=new PostgresTopologyReader(source).read(tenant,a,new EntityVisibility(true,Set.of()),at).orElseThrow();assertEquals(1,empty.nodes().size());assertTrue(empty.edges().isEmpty());for(int i=0;i<51;i++)edge(b,a,at.minusSeconds(1),null);var view=new PostgresTopologyReader(source).read(tenant,a,new EntityVisibility(true,Set.of()),at).orElseThrow();assertEquals(50,view.edges().size());assertTrue(view.truncated());assertEquals(2,view.nodes().size());}
}
