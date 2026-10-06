package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresSourceInstanceIT extends OwnedInventoryTest {
 final TenantId tenant=new TenantId("source-instance-pg-"+UUID.randomUUID());
 final Principal p=new Principal(new SubjectId("instance-fixture-owner"),tenant,Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());
 final DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
 final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
 final AtomicReference<SourceSetupService.Connection> connection=new AtomicReference<>(new SourceSetupService.Connection("sha256:"+"a".repeat(64),"fixture"));
 SourceInstanceService service(){return new SourceInstanceService(new PostgresWorkflowStore(source),(who,s)->connection.get(),Clock.systemUTC());}
 SourceSetupService setups(){return new SourceSetupService(new PostgresWorkflowStore(source),(who,t)->{throw new AssertionError();},(who,s)->connection.get(),Clock.systemUTC());}
 SourceSetup create(){return setups().confirm(p,new SourceSetupService.Command(UUID.randomUUID(),"PG Fixture instance","Synthetic metadata",new WorkflowDefinition.Source("ZABBIX_HOST","zabbix-1"),connection.get().digest(),null)).setup();}
 @Test void freshConnectionsKeepImmutablePinsAndOriginalReceipts(){
  var creation=create();assertTrue(new PostgresWorkflowStore(source).transaction(tenant,s->s.sourceInstance(p.subjectId().value(),creation.id()).isEmpty()).booleanValue());
  connection.set(new SourceSetupService.Connection("sha256:"+"b".repeat(64),"fixture"));
  var c=new SourceInstanceService.Edit(UUID.randomUUID(),1,"Rebound Fixture","Synthetic metadata",connection.get().digest(),"ACTIVE");var result=service().edit(p,creation.id(),c);
  assertEquals(2,result.instance().configurationRevision());assertEquals(result.instance(),service().read(p,creation.id()));assertEquals(result,service().edit(p,creation.id(),c));assertEquals(result,service().receipt(p,creation.id(),c.requestId()));
  var history=service().configurations(p,creation.id());assertEquals(2,history.size());assertEquals(creation.connectionDigest(),history.getLast().connectionDigest());assertEquals(creation,setups().read(p,creation.id()).setup());
  var other=new Principal(new SubjectId("other"),tenant,p.permissions(),p.resourceScope());assertTrue(service().list(other).items().isEmpty());assertThrows(WorkflowFailure.class,()->service().receipt(other,creation.id(),c.requestId()));
  var collision=assertThrows(WorkflowFailure.class,()->setups().confirm(other,new SourceSetupService.Command(creation.id(),"Other Fixture","",creation.source(),connection.get().digest(),null)));assertEquals(WorkflowFailure.Code.CONFLICT,collision.code());
  var foreign=new Principal(p.subjectId(),new TenantId(tenant.value()+"-foreign"),p.permissions(),p.resourceScope());assertTrue(service().list(foreign).items().isEmpty());
 }
 @Test void concurrentCompareAndSetCommitsOneReceipt()throws Exception{
  var creation=create();var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(4)){var futures=new ArrayList<Future<Boolean>>();for(int n=0;n<4;n++){final int index=n;futures.add(pool.submit(()->{start.await();try{service().edit(p,creation.id(),new SourceInstanceService.Edit(UUID.randomUUID(),1,"Writer "+index,"",creation.connectionDigest(),"ACTIVE"));return true;}catch(WorkflowFailure e){assertEquals(WorkflowFailure.Code.CONFLICT,e.code());return false;}}));}start.countDown();int winners=0;for(var f:futures)if(f.get(15,TimeUnit.SECONDS))winners++;assertEquals(1,winners);}
  assertEquals(2,service().read(p,creation.id()).editVersion());assertEquals(1,new PostgresWorkflowStore(source).transaction(tenant,s->s.sourceCommandCount(p.subjectId().value())).intValue());assertEquals(1,service().configurations(p,creation.id()).size());
 }
 @Test void rollbackDoesNotPublishAConfigurationOrReceipt(){
  var creation=create();var c=new SourceInstanceService.Edit(UUID.randomUUID(),1,"Changed","",creation.connectionDigest(),"ACTIVE");var result=service().edit(p,creation.id(),c);var store=new PostgresWorkflowStore(source);
  assertThrows(IllegalStateException.class,()->store.transaction(tenant,s->{s.saveSourceInstance(p.subjectId().value(),SourceInstance.initial(creation));throw new IllegalStateException("Fixture rollback");}));assertEquals(result.instance(),service().read(p,creation.id()));
 }
 @Test void corruptedCurrentPinDoesNotBecomeAValidConfiguration()throws Exception{
  var creation=create();service().edit(p,creation.id(),new SourceInstanceService.Edit(UUID.randomUUID(),1,"Maintained Fixture","",creation.connectionDigest(),"ACTIVE"));
  try(var c=source.getConnection();var q=c.prepareStatement("UPDATE integration.source_instance SET body=jsonb_set(body,'{connectionDigest}',to_jsonb(?::text)) WHERE tenant_id=? AND source_id=?")){q.setString(1,"sha256:"+"f".repeat(64));q.setString(2,tenant.value());q.setObject(3,creation.id());assertEquals(1,q.executeUpdate());}
  assertThrows(IllegalStateException.class,()->service().read(p,creation.id()));
 }
}
