package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.WorkflowMetricOutput.Receipt;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual PostgreSQL, isolated synthetic versions and proofs; point values are not persisted here. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresWorkflowMetricOutputIT extends OwnedInventoryTest {
 final TenantId tenant=new TenantId("output-pg-fixture-"+UUID.randomUUID());final String owner="output-pg-author";
 final DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
 final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","output-fixture",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
 Receipt prepare(){
  return prepare(null);
 }
 Receipt prepare(WorkflowDefinition.Source supplied){
  var mapping=com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog.load(getClass().getClassLoader()).find("zabbix","system.cpu.util[,user]").orElseThrow();
  var item=new SourceMetricDiscovery.Item("50740","10683",mapping.itemKeyExact(),"Fixture CPU","%","FLOAT","MAPPED",SourceMetricDiscovery.Mapping.from(mapping));
  var pin=supplied==null?new WorkflowDefinition.Source("ZABBIX_METRIC","output-pg-fixture",new WorkflowDefinition.ConfigurationPin(UUID.randomUUID(),1,"sha256:"+"a".repeat(64)),WorkflowMetricSourcePin.from(UUID.randomUUID(),item)):supplied;
  var nodes=List.of(new WorkflowDefinition.Node("source",WorkflowDefinition.Type.SOURCE,"1",Map.of()),new WorkflowDefinition.Node("map",WorkflowDefinition.Type.MAP,"1",Map.of("timestamp","timestamp","sourceKey","sourceKey","value","value")),new WorkflowDefinition.Node("validate",WorkflowDefinition.Type.VALIDATE,"1",Map.of()),new WorkflowDefinition.Node("output",WorkflowDefinition.Type.OUTPUT,"1",Map.of()));
  var d=WorkflowOperators.builtIn().pin(new WorkflowDefinition("output-pg",1,"Fixture metric metadata",pin,new WorkflowDefinition.Target(null,1,null,"METRIC",mapping.pin(),mapping.metricKey()),nodes,List.of(new WorkflowDefinition.Edge("source","map"),new WorkflowDefinition.Edge("map","validate"),new WorkflowDefinition.Edge("validate","output"))));
  var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var layout=new HashMap<String,WorkflowStore.Position>();for(var n:nodes)layout.put(n.id(),new WorkflowStore.Position(0,0));
  wiring.workflows().transaction(tenant,s->{s.publish(new WorkflowStore.Entry(d,d.digest(),"PUBLISHED",0,layout,now,null),owner);return null;});
  var labels=new HashMap<String,String>();labels.put("tenant_id",tenant.value());labels.put("owner_scope",WorkflowDefinition.hash(List.of(owner)).substring(7));labels.put("source_instance_id",pin.instanceId());labels.put("external_item_id",pin.metric().itemId());labels.put("host_external_id",pin.metric().hostId());labels.put("metric_key",mapping.metricKey());labels.put("unit",mapping.unit());labels.put("mapping_id",mapping.id());labels.put("mapping_revision",String.valueOf(mapping.pin().revision()));labels.put("mapping_digest",mapping.pin().digest());labels.put("workflow_id",d.id());labels.put("workflow_revision","1");labels.put("workflow_digest",d.digest());labels.put("configuration_digest",pin.configuration().digest());labels.put("data_mode","zabbix-jsonrpc");
  var batch=new MetricWriteBatch(labels,List.of(new MetricWriteBatch.Sample(now.minusSeconds(1).toEpochMilli(),new BigDecimal("0.125"))),0);
  return new Receipt(UUID.randomUUID(),d.id(),1,d.digest(),UUID.randomUUID(),"sha256:"+"b".repeat(64),now,now,"PENDING",1,0,0,0,0,1,null,labels,batch.seriesHash(),WorkflowMetricOutput.batchDigest(batch),batch.samples().stream().map(MetricWriteBatch.Sample::timestampMillis).toList());
 }
 @Test void admissionRollsBackAndUnknownProofSurvivesAReopenedStore(){
  var original=prepare();assertThrows(IllegalStateException.class,()->wiring.workflows().transaction(tenant,s->{s.addMetricOutput(owner,original);throw new IllegalStateException("Fixture rollback");}));assertTrue(wiring.workflows().transaction(tenant,s->s.metricOutput(owner,original.requestId())).isEmpty());
  wiring.workflows().transaction(tenant,s->{s.addMetricOutput(owner,original);return null;});var unknown=original.finish("UNKNOWN","OUTPUT_UNCONFIRMED",original.createdAt().plusSeconds(1));wiring.workflows().transaction(tenant,s->{s.finishMetricOutput(owner,unknown);return null;});
  var reopened=new PostgresWorkflowStore(source);assertEquals(unknown,reopened.transaction(tenant,s->s.metricOutput(owner,original.requestId()).orElseThrow()));assertEquals(List.of(unknown),reopened.transaction(tenant,s->s.metricOutputs(owner,original.workflowId())));assertTrue(reopened.transaction(tenant,s->s.metricOutput("other",original.requestId())).isEmpty());assertTrue(reopened.transaction(new TenantId(tenant.value()+"-other"),s->s.metricOutput(owner,original.requestId())).isEmpty());
  var confirmed=unknown.finish("CONFIRMED",null,unknown.updatedAt().plusSeconds(1));reopened.transaction(tenant,s->{s.finishMetricOutput(owner,confirmed);return null;});assertEquals(confirmed,wiring.workflows().transaction(tenant,s->s.metricOutput(owner,original.requestId()).orElseThrow()));
  assertThrows(IllegalStateException.class,()->reopened.transaction(tenant,s->{s.finishMetricOutput(owner,unknown);return null;}));
 }
 @Test void refinementCannotReplaceTheImmutableBatchOrDeclareAnUnknownWriteFailed(){
  var r=prepare();var unknown=r.finish("UNKNOWN","OUTPUT_UNCONFIRMED",r.createdAt());wiring.workflows().transaction(tenant,s->{s.addMetricOutput(owner,unknown);return null;});
  assertThrows(IllegalStateException.class,()->wiring.workflows().transaction(tenant,s->{s.finishMetricOutput(owner,unknown.finish("FAILED","OUTPUT_REJECTED",r.createdAt()));return null;}));
  var altered=new Receipt(r.requestId(),r.workflowId(),1,r.digest(),UUID.randomUUID(),r.commandDigest(),r.createdAt(),r.createdAt(),"CONFIRMED",1,0,0,1,0,0,null,r.labels(),r.seriesHash(),r.batchDigest(),r.timestamps());assertThrows(IllegalStateException.class,()->wiring.workflows().transaction(tenant,s->{s.finishMetricOutput(owner,altered);return null;}));assertEquals(unknown,wiring.workflows().transaction(tenant,s->s.metricOutput(owner,r.requestId()).orElseThrow()));
 }
 @Test void corruptPublicIdentifierIsNotAnAcknowledgement()throws Exception {
  var r=prepare();wiring.workflows().transaction(tenant,s->{s.addMetricOutput(owner,r);return null;});try(var c=source.getConnection();var sql=c.prepareStatement("UPDATE integration.workflow_metric_output SET body=jsonb_set(body,'{requestId}',to_jsonb(?::text)) WHERE tenant_id=? AND request_id=?")){sql.setString(1,UUID.randomUUID().toString());sql.setString(2,tenant.value());sql.setObject(3,r.requestId());assertEquals(1,sql.executeUpdate());}assertThrows(IllegalStateException.class,()->wiring.workflows().transaction(tenant,s->s.metricOutput(owner,r.requestId())));
 }

 @Test void sampleClosureIsDurableScopedAndPreservesOriginalProof()throws Exception{
  var original=prepare();var unknown=original.finish("UNKNOWN","OUTPUT_UNCONFIRMED",original.createdAt());wiring.workflows().transaction(tenant,t->{t.addMetricOutput(owner,unknown);return null;});
  var p=new com.acme.opsweave.identity.domain.Principal(new com.acme.opsweave.identity.domain.SubjectId(owner),tenant,Set.of(com.acme.opsweave.identity.domain.Permission.SOURCE_SYNC,com.acme.opsweave.identity.domain.Permission.METRIC_READ,com.acme.opsweave.identity.domain.Permission.LOG_READ,com.acme.opsweave.identity.domain.Permission.LOG_WRITE),com.acme.opsweave.identity.domain.ResourceScope.tenantWide());
  var c=new WorkflowSampleRecovery.Command(UUID.randomUUID(),unknown.workflowId(),1,unknown.digest(),WorkflowSampleRecovery.Kind.METRIC_SAMPLE,unknown.requestId(),unknown.batchDigest(),unknown.updatedAt(),true);
  var service=new com.acme.opsweave.integration.application.WorkflowSampleRecoveryService(wiring.workflows(),Clock.systemUTC());assertNull(service.status(p,c.kind(),c.batchId()).closure());var receipt=service.abandon(p,c);assertEquals("ABANDONED",receipt.state());assertEquals(c.commandDigest(),receipt.commandDigest());
  var reopened=new PostgresWorkflowStore(source);var read=new com.acme.opsweave.integration.application.WorkflowSampleRecoveryService(reopened,Clock.systemUTC());assertEquals(receipt,read.receipt(p,c.requestId()));assertEquals(receipt,read.abandon(p,c));assertEquals(receipt,read.status(p,c.kind(),c.batchId()).closure());assertEquals(unknown,reopened.transaction(tenant,t->t.metricOutput(owner,c.batchId()).orElseThrow()));
  assertTrue(reopened.transaction(tenant,t->t.sampleRecovery("other",c.requestId())).isEmpty());assertTrue(reopened.transaction(new TenantId(tenant.value()+"-other"),t->t.sampleRecovery(owner,c.requestId())).isEmpty());
  var conflict=assertThrows(WorkflowFailure.class,()->reopened.transaction(tenant,t->{t.finishMetricOutput(owner,unknown.finish("CONFIRMED",null,unknown.updatedAt().plusSeconds(1)));return null;}));assertEquals(WorkflowFailure.Code.CONFLICT,conflict.code());assertEquals(unknown,reopened.transaction(tenant,t->t.metricOutput(owner,c.batchId()).orElseThrow()));
  try(var connection=source.getConnection();var sql=connection.prepareStatement("UPDATE integration.workflow_sample_recovery SET body=jsonb_set(body,'{batchId}',to_jsonb(?::text)) WHERE tenant_id=? AND request_id=?")){sql.setString(1,UUID.randomUUID().toString());sql.setString(2,tenant.value());sql.setObject(3,c.requestId());assertEquals(1,sql.executeUpdate());}assertThrows(IllegalStateException.class,()->read.receipt(p,c.requestId()));
 }

 @Test @EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_VM_URL",matches=".+") void actualMetricPointSurvivesSampleClosure()throws Exception{
  var original=prepare();var batch=new MetricWriteBatch(original.labels(),List.of(new MetricWriteBatch.Sample(original.timestamps().getFirst(),new BigDecimal("0.125"))),0);var sink=new com.acme.opsweave.platform.telemetry.VictoriaWorkflowMetricSink(java.net.URI.create(System.getenv("OPSWEAVE_TEST_VM_URL")),Duration.ZERO);try{sink.write(batch);}catch(com.acme.opsweave.integration.application.WorkflowMetricOutputService.OutputFailure uncertain){assertTrue(uncertain.unknown(),"A definite refusal must fail this actual-output test");}
  var unknown=original.finish("UNKNOWN","OUTPUT_UNCONFIRMED",original.createdAt());wiring.workflows().transaction(tenant,t->{t.addMetricOutput(owner,unknown);return null;});

  var principal=new com.acme.opsweave.identity.domain.Principal(new com.acme.opsweave.identity.domain.SubjectId(owner),tenant,Set.of(com.acme.opsweave.identity.domain.Permission.SOURCE_SYNC,com.acme.opsweave.identity.domain.Permission.METRIC_READ,com.acme.opsweave.identity.domain.Permission.LOG_READ,com.acme.opsweave.identity.domain.Permission.LOG_WRITE),com.acme.opsweave.identity.domain.ResourceScope.tenantWide());
  var command=new WorkflowSampleRecovery.Command(UUID.randomUUID(),unknown.workflowId(),1,unknown.digest(),WorkflowSampleRecovery.Kind.METRIC_SAMPLE,unknown.requestId(),unknown.batchDigest(),unknown.updatedAt(),true);var service=new com.acme.opsweave.integration.application.WorkflowSampleRecoveryService(wiring.workflows(),Clock.systemUTC());var receipt=service.abandon(principal,command);assertEquals(receipt,service.abandon(principal,command));assertEquals("UNKNOWN",new PostgresWorkflowStore(source).transaction(tenant,t->t.metricOutput(owner,unknown.requestId()).orElseThrow()).state());

  List<MetricWriteBatch.Sample> points=List.of();for(int i=0;i<30&&points.isEmpty();i++){points=sink.read(original.labels(),original.timestamps());if(points.isEmpty())Thread.sleep(500);}assertEquals(1,points.size());assertEquals(0,new BigDecimal("0.125").compareTo(points.getFirst().value()));assertEquals(unknown.batchDigest(),WorkflowMetricOutput.batchDigest(new MetricWriteBatch(original.labels(),points,0)));assertEquals(unknown,wiring.workflows().transaction(tenant,t->t.metricOutput(owner,unknown.requestId()).orElseThrow()));
 }
}
