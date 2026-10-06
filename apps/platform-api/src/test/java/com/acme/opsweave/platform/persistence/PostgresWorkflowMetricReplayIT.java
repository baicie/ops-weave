package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowMetricReplay.*;
import com.acme.opsweave.platform.telemetry.VictoriaWorkflowMetricSink;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real PG and metric engine; explicitly synthetic source data and isolated tenant/series. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_VM_URL",matches=".+")
class PostgresWorkflowMetricReplayIT extends PostgresWorkflowMetricOutputIT {
    final Principal p = new Principal(new SubjectId(owner), tenant, Set.of(Permission.SOURCE_SYNC, Permission.METRIC_READ, Permission.WORKFLOW_REPLAY), ResourceScope.tenantWide());
    final AtomicInteger reads = new AtomicInteger();
    WorkflowMetricReplayService service(WorkflowMetricOutputService.Sink sink, String value) {
        var mapping = com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog.load(getClass().getClassLoader()).find("zabbix", "system.cpu.util[,user]").orElseThrow();
        var sources = new WorkflowService.Sources() { public void require(Principal p, WorkflowDefinition.Source src, com.acme.opsweave.integration.api.WorkflowStore.Session s, boolean available) {}
            public void requireTarget(Principal p, WorkflowDefinition.Source src, WorkflowDefinition.Target t, com.acme.opsweave.integration.api.WorkflowStore.Session s, boolean available) { assertEquals(mapping.pin(), t.mappingPin()); } };
        var store = new PostgresWorkflowStore(source); var workflows = new WorkflowService(store, (p,t)->{throw new AssertionError();}, (p,s,id)->{throw new AssertionError("No preview reused as historical source");}, sources, (p,pin)->mapping, Clock.systemUTC());
        return new WorkflowMetricReplayService(store, workflows, (p,src,from,till)->{reads.incrementAndGet(); return List.of(Map.of("timestamp",from.plusSeconds(1).toString(),"sourceKey",src.metric().sourceKey(),"value",value));}, sink, new Semaphore(2), Clock.systemUTC());
    }
    Command command(WorkflowMetricOutput.Receipt parent) { var from = Instant.now().minusSeconds(180).truncatedTo(java.time.temporal.ChronoUnit.SECONDS); return new Command(UUID.randomUUID(), parent.workflowId(), 1, parent.digest(), from, from.plusSeconds(60)); }
    Execute execute(Plan plan) { return new Execute(UUID.randomUUID(), plan.requestId(), plan.proof().inputDigest(), plan.proof().batchDigest()); }

    @Test void actualHistoricalPointAndOriginalReceiptSurviveStoreReopen() throws Exception {
        var parent = prepare(); var actual = new VictoriaWorkflowMetricSink(URI.create(System.getenv("OPSWEAVE_TEST_VM_URL")), Duration.ofSeconds(5)); var writes = new AtomicInteger();
        var sink = new WorkflowMetricOutputService.Sink() { public void write(MetricWriteBatch b) { writes.incrementAndGet(); actual.write(b); } public List<MetricWriteBatch.Sample> read(Map<String,String> l,List<Long> t) { return actual.read(l,t); } };
        var runtime = service(sink,"12.5"); var c = command(parent); var plan = runtime.create(p,c); assertEquals("READY",plan.state()); assertEquals(0,writes.get()); assertEquals(1,reads.get());
        var execute = execute(plan); var result = runtime.execute(p,execute); assertTrue(Set.of("UNKNOWN","CONFIRMED").contains(result.state())); assertEquals(1,writes.get()); assertEquals(2,reads.get());
        // Test-only visibility probes preserve the actual UNKNOWN acknowledgement; no source or write retry.
        for(int i=0;i<30&&!result.state().equals("CONFIRMED");i++){Thread.sleep(200);result=runtime.verify(p,execute.requestId());}
        assertEquals("CONFIRMED",result.state());
        var found = actual.read(plan.proof().labels(),plan.proof().timestamps()); assertEquals(1,found.size()); assertEquals("0.125",found.getFirst().value().stripTrailingZeros().toPlainString());
        var reopened = service(sink,"99"); assertEquals(plan,reopened.create(p,c)); assertEquals(result,reopened.execute(p,execute)); assertEquals(result,reopened.executionForPlan(p,plan.requestId())); assertEquals(2,reads.get()); assertEquals(1,writes.get());
        assertThrows(WorkflowFailure.class,()->reopened.execute(p,execute(plan))); assertEquals(1,writes.get()); assertFalse(plan.notifications()); assertFalse(plan.actions());
        var export=System.getenv("OPSWEAVE_TEST_REPLAY_DIR");if(export!=null){var folder=java.nio.file.Path.of(export);java.nio.file.Files.createDirectories(folder);java.nio.file.Files.writeString(folder.resolve("actual-plan.json"),com.acme.opsweave.platform.catalog.CatalogJson.JSON.writeValueAsString(plan));java.nio.file.Files.writeString(folder.resolve("actual-receipt.json"),com.acme.opsweave.platform.catalog.CatalogJson.JSON.writeValueAsString(result));}
        assertTrue(wiring.workflows().transaction(tenant,s->s.metricStreamTask(owner,parent.workflowId())).isEmpty());
        var other = new Principal(new SubjectId("other-replay-owner"),tenant,p.permissions(),ResourceScope.tenantWide()); assertThrows(WorkflowFailure.class,()->reopened.plan(other,plan.requestId()));
        try(var c0=source.getConnection();var q=c0.prepareStatement("SELECT body::text FROM integration.workflow_metric_replay_plan WHERE tenant_id=? AND request_id=?")){q.setString(1,tenant.value());q.setObject(2,plan.requestId());try(var row=q.executeQuery()){assertTrue(row.next());assertFalse(row.getString(1).contains("12.5"));assertFalse(row.getString(1).contains("0.125"));}}
    }
    @Test void actualLostAcknowledgementIsOnlyReadBackWithoutSourceRereadOrWrite() throws Exception {
        var parent=prepare();var actual=new VictoriaWorkflowMetricSink(URI.create(System.getenv("OPSWEAVE_TEST_VM_URL")),Duration.ofSeconds(5));var writes=new AtomicInteger();
        var lost=new WorkflowMetricOutputService.Sink(){public void write(MetricWriteBatch b){writes.incrementAndGet();actual.write(b);throw new WorkflowMetricOutputService.OutputFailure(true);}public List<MetricWriteBatch.Sample> read(Map<String,String> l,List<Long> t){return actual.read(l,t);}};
        var runtime=service(lost,"12.5");var plan=runtime.create(p,command(parent));var execute=execute(plan);var result=runtime.execute(p,execute);assertEquals("UNKNOWN",result.state());
        var reopened=service(lost,"99");assertEquals(result,reopened.execute(p,execute));assertEquals(1,writes.get());assertEquals(2,reads.get());
        // Explicit test-only bounded visibility probes; the production service never polls or resends.
        for(int i=0;i<30&&!result.state().equals("CONFIRMED");i++){Thread.sleep(200);result=reopened.verify(p,execute.requestId());}
        assertEquals("CONFIRMED",result.state());assertEquals(1,writes.get());assertEquals(2,reads.get());assertEquals(1,actual.read(plan.proof().labels(),plan.proof().timestamps()).size());
    }
    @Test void changedInputIsRejectedAndCorruptIdentityCannotBeReadAsOriginal() throws Exception {
        var parent=prepare();var writes=new AtomicInteger();var sink=new WorkflowMetricOutputService.Sink(){public void write(MetricWriteBatch b){writes.incrementAndGet();}public List<MetricWriteBatch.Sample> read(Map<String,String> l,List<Long> t){throw new AssertionError();}};
        var original=service(sink,"12.5");var plan=original.create(p,command(parent));var changed=service(sink,"13.5");var result=changed.execute(p,execute(plan));assertEquals("FAILED",result.state());assertEquals("SOURCE_WINDOW_CHANGED",result.error());assertEquals(0,writes.get());
        try(var c=source.getConnection();var q=c.prepareStatement("UPDATE integration.workflow_metric_replay_plan SET body=jsonb_set(body,'{requestId}',to_jsonb(?::text)) WHERE tenant_id=? AND request_id=?")){q.setString(1,UUID.randomUUID().toString());q.setString(2,tenant.value());q.setObject(3,plan.requestId());assertEquals(1,q.executeUpdate());}
        assertThrows(IllegalStateException.class,()->original.plan(p,plan.requestId()));assertEquals(0,writes.get());
    }
    @Test void concurrentSameCommandHasOnlyOneAdmittedSourceAndOutputAttempt() throws Exception {
        var parent=prepare();var writes=new AtomicInteger();var sink=new WorkflowMetricOutputService.Sink(){public void write(MetricWriteBatch b){writes.incrementAndGet();try{Thread.sleep(100);}catch(InterruptedException e){throw new AssertionError(e);}}public List<MetricWriteBatch.Sample> read(Map<String,String> l,List<Long> t){throw new AssertionError("No implicit verification");}};
        var runtime=service(sink,"12.5");var plan=runtime.create(p,command(parent));var execute=execute(plan);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var first=pool.submit(()->runtime.execute(p,execute));var second=pool.submit(()->runtime.execute(p,execute));var a=first.get(15,java.util.concurrent.TimeUnit.SECONDS);var b=second.get(15,java.util.concurrent.TimeUnit.SECONDS);assertEquals(a.requestId(),b.requestId());assertTrue(Set.of("PENDING","CONFIRMED").contains(a.state()));assertTrue(Set.of("PENDING","CONFIRMED").contains(b.state()));}
        assertEquals(1,writes.get());assertEquals(2,reads.get());assertEquals("CONFIRMED",runtime.receipt(p,execute.requestId()).state());
    }
    @Test void migrationRollbackRefusesRetainedSelectionsAndEmptyForwardReopens() throws Exception {
        var schema="replay_migration_fixture_"+UUID.randomUUID().toString().replace("-","");assertTrue(schema.matches("replay_migration_fixture_[a-f0-9]{32}"));var root=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!java.nio.file.Files.exists(root.resolve("db/migrations/platform/V056__workflow_metric_replay.sql")))root=root.getParent();var forward=java.nio.file.Files.readString(root.resolve("db/migrations/platform/V056__workflow_metric_replay.sql")).replace("integration.",schema+".");var rollback=java.nio.file.Files.readString(root.resolve("db/rollback/platform/V056__workflow_metric_replay.sql")).replace("integration.",schema+".");
        try(var c=source.getConnection();var q=c.createStatement()){try{q.execute("CREATE SCHEMA "+schema);q.execute("CREATE TABLE "+schema+".workflow_version(tenant_id text,workflow_id text,revision integer,PRIMARY KEY(tenant_id,workflow_id,revision))");q.execute("INSERT INTO "+schema+".workflow_version VALUES ('fixture','fixture',1)");q.execute(forward);q.execute(rollback);q.execute(forward);q.execute("INSERT INTO "+schema+".workflow_metric_replay_plan VALUES ('fixture','fixture','10000000-0000-4000-8000-000000000136','fixture',1,'{\"fixture\":true}',now())");assertThrows(java.sql.SQLException.class,()->q.execute(rollback));try(var r=q.executeQuery("SELECT body::text FROM "+schema+".workflow_metric_replay_plan")){assertTrue(r.next());assertEquals("{\"fixture\": true}",r.getString(1));}}finally{q.execute("DROP SCHEMA "+schema+" CASCADE");}}
    }
}
