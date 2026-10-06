package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowLogReplay.*;
import com.acme.opsweave.platform.telemetry.ClickHouseWorkflowLogWindowSink;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual PG and dedicated ClickHouse output; source data is an explicit synthetic fixture. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_LOGS_URL",matches=".+")
class PostgresWorkflowLogReplayIT extends PostgresWorkflowLogStreamIT {
    final Principal replayPrincipal=new Principal(new SubjectId(owner),tenant,Set.of(Permission.SOURCE_SYNC,Permission.ENTITY_READ,Permission.LOG_READ,Permission.LOG_WRITE,Permission.WORKFLOW_REPLAY),ResourceScope.tenantWide());
    final AtomicInteger reads=new AtomicInteger();
    WorkflowLogWindowSink actual(){return ClickHouseWorkflowLogWindowSink.replay(URI.create(System.getenv("OPSWEAVE_TEST_LOGS_URL")),System.getenv("OPSWEAVE_TEST_LOGS_USER"),System.getenv("OPSWEAVE_TEST_LOGS_PASSWORD"));}
    WorkflowLogReplayService replay(WorkflowStore store,WorkflowLogWindowSink sink,Time clock,int count){return new WorkflowLogReplayService(store,flows(store,clock),(who,src,from,till)->{reads.incrementAndGet();return rows(src,from,count);},sink,new Semaphore(2),clock);}
    Command command(WorkflowDefinition d,Time clock){var from=clock.instant().minusSeconds(180).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);return new Command(UUID.randomUUID(),d.id(),1,d.digest(),from,from.plusSeconds(60));}
    Execute execution(Plan p){return new Execute(UUID.randomUUID(),p.requestId(),p.proof().inputDigest(),p.proof().batchDigest());}
    @Test void actualProjectionConfirmedAndOriginalUUIDSurviveReopenWithoutSourceOrOutputRetry()throws Exception{
        var d=definition();var time=new Time();var actual=actual();assertTrue(actual.ready());var writes=new AtomicInteger();
        var sink=new WorkflowLogWindowSink(){public boolean ready(){return actual.ready();}public void write(WorkflowLogWindow.Batch b){writes.incrementAndGet();actual.write(b);}public List<WorkflowLogWindow.Record> read(WorkflowLogOutput.Scope s){return actual.read(s);}};
        var runtime=replay(wiring.workflows(),sink,time,1000);var c=command(d,time);var plan=runtime.create(replayPrincipal,c);assertEquals("READY",plan.state());assertEquals(1000,plan.proof().inputCount());assertEquals(0,writes.get());
        var execution=execution(plan);var receipt=runtime.execute(replayPrincipal,execution);assertEquals("CONFIRMED",receipt.state());assertEquals(1,writes.get());assertEquals(2,reads.get());
        var data=actual.read(plan.proof().scope());assertEquals(1000,data.size());assertTrue(data.getFirst().body().contains("🧵"));assertTrue(data.getFirst().body().contains("<script>"));
        var first=runtime.data(replayPrincipal,plan.requestId(),-1);assertTrue(first.complete());assertEquals(50,first.records().size());assertEquals(49,first.nextIndex());
        var second=runtime.data(replayPrincipal,plan.requestId(),49);assertTrue(second.complete());assertEquals(50,second.records().getFirst().index());assertEquals(99,second.nextIndex());assertEquals(2,reads.get());assertEquals(1,writes.get());
        var normal=new ClickHouseWorkflowLogWindowSink(URI.create(System.getenv("OPSWEAVE_TEST_LOGS_URL")),System.getenv("OPSWEAVE_TEST_LOGS_USER"),System.getenv("OPSWEAVE_TEST_LOGS_PASSWORD"));assertTrue(normal.read(plan.proof().scope()).isEmpty());
        var reopened=replay(new PostgresWorkflowStore(source),sink,time,1);assertEquals(plan,reopened.create(replayPrincipal,c));assertEquals(receipt,reopened.execute(replayPrincipal,execution));assertEquals(receipt,reopened.executionForPlan(replayPrincipal,c.requestId()));assertEquals(2,reads.get());assertEquals(1,writes.get());
        assertThrows(WorkflowFailure.class,()->reopened.execute(replayPrincipal,execution(plan)));assertTrue(wiring.workflows().transaction(tenant,s->s.logStreamTask(owner,d.id())).isEmpty());
        try(var connection=source.getConnection();var q=connection.prepareStatement("SELECT body::text FROM integration.workflow_log_replay_plan WHERE tenant_id=? AND request_id=?")){q.setString(1,tenant.value());q.setObject(2,plan.requestId());try(var row=q.executeQuery()){assertTrue(row.next());assertFalse(row.getString(1).contains("Synthetic Fixture"));assertFalse(row.getString(1).contains("<script>"));assertFalse(row.getString(1).contains("severityText"));}}
        var export=System.getenv("OPSWEAVE_TEST_REPLAY_DIR");if(export!=null){var directory=java.nio.file.Path.of(export);java.nio.file.Files.createDirectories(directory);java.nio.file.Files.writeString(directory.resolve("actual-log-plan.json"),com.acme.opsweave.platform.catalog.CatalogJson.JSON.writeValueAsString(plan));java.nio.file.Files.writeString(directory.resolve("actual-log-receipt.json"),com.acme.opsweave.platform.catalog.CatalogJson.JSON.writeValueAsString(receipt));}
    }
    @Test void lostWriteAcknowledgementOnlyUsesExplicitOriginalReadback(){
        var d=definition();var time=new Time();var actual=actual();var writes=new AtomicInteger();
        var sink=new WorkflowLogWindowSink(){public boolean ready(){return actual.ready();}public void write(WorkflowLogWindow.Batch b){writes.incrementAndGet();actual.write(b);throw new WorkflowLogOutputService.OutputFailure(true);}public List<WorkflowLogWindow.Record> read(WorkflowLogOutput.Scope s){return actual.read(s);}};
        var runtime=replay(wiring.workflows(),sink,time,2);var plan=runtime.create(replayPrincipal,command(d,time));var e=execution(plan);var receipt=runtime.execute(replayPrincipal,e);assertEquals("UNKNOWN",receipt.state());assertEquals(receipt,runtime.execute(replayPrincipal,e));assertEquals(2,reads.get());assertEquals(1,writes.get());
        assertEquals("CONFIRMED",runtime.verify(replayPrincipal,e.requestId()).state());assertEquals(2,reads.get());assertEquals(1,writes.get());
    }
    @Test void changedInputAndDamagedScopeRejectWithoutAnyOutput()throws Exception{
        var d=definition();var time=new Time();var writes=new AtomicInteger();var sink=new WorkflowLogWindowSink(){public boolean ready(){return true;}public void write(WorkflowLogWindow.Batch b){writes.incrementAndGet();throw new AssertionError();}public List<WorkflowLogWindow.Record> read(WorkflowLogOutput.Scope s){throw new AssertionError();}};
        var runtime=replay(wiring.workflows(),sink,time,2);var plan=runtime.create(replayPrincipal,command(d,time));var changed=replay(wiring.workflows(),sink,time,1);assertEquals("SOURCE_WINDOW_CHANGED",changed.execute(replayPrincipal,execution(plan)).error());assertEquals(0,writes.get());
        try(var c=source.getConnection();var q=c.prepareStatement("UPDATE integration.workflow_log_replay_plan SET body=jsonb_set(body,'{proof,scope,ownerScope}',to_jsonb(?::text)) WHERE tenant_id=? AND request_id=?")){q.setString(1,"b".repeat(64));q.setString(2,tenant.value());q.setObject(3,plan.requestId());assertEquals(1,q.executeUpdate());}
        assertThrows(IllegalStateException.class,()->runtime.plan(replayPrincipal,plan.requestId()));assertEquals(0,writes.get());
    }
    @Test void concurrentOriginalCommandAdmitsOneOutputAndPreservesPendingQuery()throws Exception{
        var d=definition();var time=new Time();var actual=actual();var writes=new AtomicInteger();var reading=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var sink=new WorkflowLogWindowSink(){public boolean ready(){return actual.ready();}public void write(WorkflowLogWindow.Batch b){writes.incrementAndGet();actual.write(b);}public List<WorkflowLogWindow.Record> read(WorkflowLogOutput.Scope s){return actual.read(s);}};
        var runtime=new WorkflowLogReplayService(wiring.workflows(),flows(wiring.workflows(),time),(who,src,from,till)->{if(reads.incrementAndGet()==2){reading.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Test source barrier expired");}catch(InterruptedException e){throw new AssertionError(e);}}return rows(src,from,2);},sink,new Semaphore(2),time);
        var plan=runtime.create(replayPrincipal,command(d,time));var execute=execution(plan);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var first=pool.submit(()->runtime.execute(replayPrincipal,execute));assertTrue(reading.await(10,java.util.concurrent.TimeUnit.SECONDS));try{var second=pool.submit(()->runtime.execute(replayPrincipal,execute));assertEquals("PENDING",second.get(10,java.util.concurrent.TimeUnit.SECONDS).state());assertEquals("PENDING",runtime.verify(replayPrincipal,execute.requestId()).state());assertEquals(0,writes.get());}finally{release.countDown();}assertEquals("CONFIRMED",first.get(10,java.util.concurrent.TimeUnit.SECONDS).state());}
        assertEquals(2,reads.get());assertEquals(1,writes.get());
    }
    @Test void metadataMigrationRollbackPreservesAnyRetainedPlan()throws Exception{
        var schema="log_replay_migration_fixture_"+UUID.randomUUID().toString().replace("-","");assertTrue(schema.matches("log_replay_migration_fixture_[a-f0-9]{32}"));var root=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!java.nio.file.Files.exists(root.resolve("db/migrations/platform/V057__workflow_log_replay.sql")))root=root.getParent();var forward=java.nio.file.Files.readString(root.resolve("db/migrations/platform/V057__workflow_log_replay.sql")).replace("integration.",schema+".");var rollback=java.nio.file.Files.readString(root.resolve("db/rollback/platform/V057__workflow_log_replay.sql")).replace("integration.",schema+".");
        try(var connection=source.getConnection();var q=connection.createStatement()){try{q.execute("CREATE SCHEMA "+schema);q.execute("CREATE TABLE "+schema+".workflow_version(tenant_id text,workflow_id text,revision integer,PRIMARY KEY(tenant_id,workflow_id,revision))");q.execute("INSERT INTO "+schema+".workflow_version VALUES ('fixture','fixture',1)");q.execute(forward);q.execute(rollback);q.execute(forward);q.execute("INSERT INTO "+schema+".workflow_log_replay_plan VALUES ('fixture','fixture','10000000-0000-4000-8000-000000000137','fixture',1,'{\"fixture\":true}',now())");assertThrows(java.sql.SQLException.class,()->q.execute(rollback));try(var row=q.executeQuery("SELECT body::text FROM "+schema+".workflow_log_replay_plan")){assertTrue(row.next());assertEquals("{\"fixture\": true}",row.getString(1));}}finally{q.execute("DROP SCHEMA "+schema+" CASCADE");}}
    }
}
