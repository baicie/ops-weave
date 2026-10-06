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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresSourceInspectionIT extends OwnedInventoryTest {
 final TenantId tenant=new TenantId("source-inspection-pg-"+UUID.randomUUID());final Principal p=new Principal(new SubjectId("inspection-fixture-owner"),tenant,Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());
 final DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
 final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","fixture-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
 final SourceSetupService.Connections connections=(who,s)->new SourceSetupService.Connection("sha256:"+"a".repeat(64),"fixture");
 SourceSetup create(){return new SourceSetupService(new PostgresWorkflowStore(source),(who,t)->{throw new AssertionError();},connections,Clock.systemUTC()).confirm(p,new SourceSetupService.Command(UUID.randomUUID(),"PG Fixture inspection","",new WorkflowDefinition.Source("ZABBIX_HOST","fixture-1"),"sha256:"+"a".repeat(64),null)).setup();}
 SourceInspectionService service(SourceInspectionService.Reader reader){return new SourceInspectionService(new PostgresWorkflowStore(source),connections,reader,Clock.systemUTC());}
 SourceInspectionService.Result result(){return new SourceInspectionService.Result(new SourceInspection.Check(true,"LABELED_FIXTURE",null),null);}
 @Test void freshAdaptersKeepOriginalResultAndOwnerScope(){
  var c=create();var calls=new AtomicInteger();var command=new SourceInspectionService.Command(UUID.randomUUID(),1,c.connectionDigest());var receipt=service((who,s,k)->{calls.incrementAndGet();return result();}).run(p,c.id(),"TEST",command);
  var reopened=service((who,s,k)->{throw new AssertionError("No repeat network call");});assertEquals(receipt,reopened.read(p,c.id(),command.requestId()));assertEquals(receipt,reopened.run(p,c.id(),"TEST",command));assertEquals(1,calls.get());assertEquals(1,reopened.recent(p,c.id()).size());
  var other=new Principal(new SubjectId("other"),tenant,p.permissions(),p.resourceScope());assertThrows(WorkflowFailure.class,()->reopened.read(other,c.id(),command.requestId()));var foreign=new Principal(p.subjectId(),new TenantId(tenant.value()+"-foreign"),p.permissions(),p.resourceScope());assertThrows(WorkflowFailure.class,()->reopened.read(foreign,c.id(),command.requestId()));
  assertThrows(IllegalStateException.class,()->new PostgresWorkflowStore(source).transaction(tenant,s->{s.finishSourceInspection(p.subjectId().value(),receipt.inspection().unknown());return null;}));assertEquals(receipt,reopened.read(p,c.id(),command.requestId()));
 }
 @Test void multipleAdaptersClaimTheSameReadOnlyOnce()throws Exception{
  var c=create();var calls=new AtomicInteger();var ready=new CountDownLatch(1);var command=new SourceInspectionService.Command(UUID.randomUUID(),1,c.connectionDigest());
  try(var pool=Executors.newFixedThreadPool(4)){var futures=new ArrayList<Future<SourceInspectionService.View>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{ready.await();return service((who,s,k)->{calls.incrementAndGet();return result();}).run(p,c.id(),"TEST",command);}));ready.countDown();for(var f:futures){var r=f.get(15,TimeUnit.SECONDS);assertEquals(command.requestId(),r.inspection().requestId());assertTrue(Set.of("PENDING","COMPLETED").contains(r.inspection().state()));}}
  assertEquals(1,calls.get());assertEquals("COMPLETED",service((who,s,k)->{throw new AssertionError();}).read(p,c.id(),command.requestId()).inspection().state());assertEquals(1,new PostgresWorkflowStore(source).transaction(tenant,s->s.sourceInspectionCount(p.subjectId().value())).intValue());
 }
 @Test void lostExecutorRemainsUnknownAndCorruptPinsFailClosed()throws Exception{
  var c=create();var i=SourceInstance.initial(c);var request=UUID.randomUUID();var past=Instant.now().minusSeconds(70).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  // This source must exist before the lost claim; preserve valid temporal lineage in the fixture.
  var old=new SourceSetup(c.id(),c.name(),c.description(),c.source(),c.connectionDigest(),c.dataMode(),c.initialTarget(),c.digest(),past.minusSeconds(1));
  try(var db=source.getConnection();var q=db.prepareStatement("UPDATE integration.source_setup SET body=jsonb_set(body,'{createdAt}',to_jsonb(?::text)),created_at=? WHERE tenant_id=? AND owner_subject=? AND setup_id=?")){q.setString(1,old.createdAt().toString());q.setTimestamp(2,java.sql.Timestamp.from(old.createdAt()));q.setString(3,tenant.value());q.setString(4,p.subjectId().value());q.setObject(5,c.id());assertEquals(1,q.executeUpdate());}
  var pending=SourceInspection.pending(c.id(),request,"TEST",SourceInstance.initial(old),past);new PostgresWorkflowStore(source).transaction(tenant,s->{s.addSourceInspection(p.subjectId().value(),pending);return null;});var reopened=service((who,s,k)->{throw new AssertionError("No repeat after lost executor");});assertEquals("UNKNOWN",reopened.read(p,c.id(),request).inspection().state());assertEquals("UNKNOWN",reopened.run(p,c.id(),"TEST",new SourceInspectionService.Command(request,1,c.connectionDigest())).inspection().state());
  try(var db=source.getConnection();var q=db.prepareStatement("UPDATE integration.source_inspection SET body=jsonb_set(body,'{connectionDigest}',to_jsonb(?::text)) WHERE tenant_id=? AND request_id=?")){q.setString(1,"sha256:"+"f".repeat(64));q.setString(2,tenant.value());q.setObject(3,request);assertEquals(1,q.executeUpdate());}
  assertThrows(IllegalStateException.class,()->reopened.read(p,c.id(),request));
 }
 @Test void boundedMetricMetadataSurvivesFreshAdaptersBeyondHostMetadataBudget()throws Exception{
  var c=create();var rows=java.util.stream.IntStream.rangeClosed(1,20).mapToObj(n->new SourceMetricDiscovery.Item(""+n,"101","fixture.long."+"x".repeat(1000),"Fixture "+n,"","FLOAT","NO_MAPPING",null)).toList();var d=new SourceMetricDiscovery(rows,false,"FIRST_PAGE_MATCH","INCOMPLETE",SourceMetricDiscovery.digest(rows));var command=new SourceInspectionService.Command(UUID.randomUUID(),1,c.connectionDigest());var receipt=service((who,s,k)->new SourceInspectionService.Result(null,null,d)).run(p,c.id(),"DISCOVER_METRICS",command);
  assertEquals("UNVERIFIED",receipt.validity());var reopened=service((who,s,k)->{throw new AssertionError("No repeated discovery");});assertEquals(receipt,reopened.run(p,c.id(),"DISCOVER_METRICS",command));assertEquals(receipt,reopened.read(p,c.id(),command.requestId()));assertEquals(20,reopened.recent(p,c.id()).getFirst().inspection().metricDiscovery().items().size());
  // Old host receipts remain valid without the newly added optional projection.
  var test=new SourceInspectionService.Command(UUID.randomUUID(),1,c.connectionDigest());var original=service((who,s,k)->result()).run(p,c.id(),"TEST",test);
  try(var db=source.getConnection();var q=db.prepareStatement("UPDATE integration.source_inspection SET body=body-'metricDiscovery' WHERE tenant_id=? AND request_id=?")){q.setString(1,tenant.value());q.setObject(2,test.requestId());assertEquals(1,q.executeUpdate());}
  assertEquals(original,reopened.read(p,c.id(),test.requestId()));
 }
 @Test void concurrentMetricPageClaimsDoNotRepeatSourceIo()throws Exception{
  var c=create();var calls=new AtomicInteger();var ready=new CountDownLatch(1);var command=new SourceInspectionService.Command(UUID.randomUUID(),1,c.connectionDigest(),null);
  var reader=new SourceInspectionService.Reader(){
   public SourceInspectionService.Result read(Principal p,WorkflowDefinition.Source source,String kind){throw new AssertionError();}
   public SourceMetricPage readMetricPage(Principal p,SourceInstance instance,SourceInspection pending,SourceMetricPage previous){calls.incrementAndGet();return SourceMetricPage.verified(SourceMetricPage.Manifest.capture(pending.requestId(),pending.asOf(),List.of()),0,List.of(),"LABELED_FIXTURE");}
  };
  try(var pool=Executors.newFixedThreadPool(4)){var futures=new ArrayList<Future<SourceInspectionService.View>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{ready.await();return service(reader).run(p,c.id(),"DISCOVER_METRIC_PAGE",command);}));ready.countDown();for(var f:futures){var r=f.get(15,TimeUnit.SECONDS);assertEquals(command.requestId(),r.inspection().requestId());assertTrue(Set.of("PENDING","COMPLETED").contains(r.inspection().state()));}}
  assertEquals(1,calls.get());assertEquals("COMPLETED",service(reader).read(p,c.id(),command.requestId()).inspection().state());assertEquals(1,calls.get());
 }
 @Test void privateThousandIdMembershipSurvivesReopenedAdapterAndCannotBeForged()throws Exception{
  var c=create();var ids=java.util.stream.IntStream.rangeClosed(1,1000).mapToObj(Integer::toString).toList();var command=new SourceInspectionService.Command(UUID.randomUUID(),1,c.connectionDigest(),null);
  var reader=new SourceInspectionService.Reader(){
   public SourceInspectionService.Result read(Principal p,WorkflowDefinition.Source source,String kind){throw new AssertionError();}
   public SourceMetricPage readMetricPage(Principal p,SourceInstance instance,SourceInspection pending,SourceMetricPage previous){
    var manifest=previous==null?SourceMetricPage.Manifest.capture(pending.requestId(),pending.asOf(),ids):previous.manifest();int offset=previous==null?0:previous.nextOffset();
    return SourceMetricPage.verified(manifest,offset,ids.subList(offset,offset+20).stream().map(id->new SourceMetricDiscovery.Item(id,"101","fixture.long."+"x".repeat(1000),"Fixture "+id,"","FLOAT","NO_MAPPING",null)).toList(),"LABELED_FIXTURE");
   }
  };
  var first=service(reader).run(p,c.id(),"DISCOVER_METRIC_PAGE",command);var reopened=service(reader);assertEquals(first,reopened.read(p,c.id(),command.requestId()));assertEquals(1000,first.inspection().metricPage().manifest().total());
  var next=reopened.run(p,c.id(),"DISCOVER_METRIC_PAGE",new SourceInspectionService.Command(UUID.randomUUID(),1,c.connectionDigest(),command.requestId()));assertEquals(20,next.inspection().metricPage().offset());assertEquals(first.inspection().metricPage().manifest(),next.inspection().metricPage().manifest());
  try(var db=source.getConnection();var q=db.prepareStatement("UPDATE integration.source_inspection SET body=jsonb_set(body,'{metricMembership}',jsonb_build_array('1'::text)) WHERE tenant_id=? AND request_id=?")){q.setString(1,tenant.value());q.setObject(2,next.inspection().requestId());assertEquals(1,q.executeUpdate());}
  assertThrows(IllegalStateException.class,()->reopened.read(p,c.id(),next.inspection().requestId()));
 }

}
