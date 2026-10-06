package com.acme.opsweave.platform.persistence;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowHostSchedule.*;
import com.acme.opsweave.identity.domain.Principal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual PG and inventory, source protocol is an explicit bounded fixture. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresWorkflowHostScheduleIT extends PostgresWorkflowHostRuntimeIT {
 static final class MutableClock extends Clock {Instant time=Instant.now().minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return time;}}
 final MutableClock clock=new MutableClock();
 @Override WorkflowRuntimeService runtime(){var sink=new WorkflowEntityOutput(wiring.writer());return new WorkflowRuntimeService(new PostgresWorkflowStore(source),(who,target)->model,(who,s,id)->{throw new AssertionError("No preview reread");},(who,s,at,id)->{throw new AssertionError("No legacy source");},new WorkflowRuntimeService.Output(){
  public void validate(Principal who,WorkflowDefinition flow,WorkflowRuntime.Settings config,List<Map<String,Object>> input,WorkflowEvaluation evaluation){sink.validate(who,flow,config,input,evaluation);}
  public String write(Principal who,WorkflowDefinition flow,WorkflowRuntime.Settings config,UUID id,Instant time,Map<String,Object> input,Map<String,Object> output,String origin){writes++;var result=sink.write(who,flow,config,id,time,input,output,origin);if(failAfterWrite&&writes==2)throw new IllegalStateException("Fixture response lost after durable write");return result;}
 },clock,new WorkflowHostRuntimeService.Sources(){public void require(WorkflowStore.Session session,Principal who,WorkflowDefinition.Source source){if(revoked)throw new WorkflowFailure(WorkflowFailure.Code.AUTHORIZATION_REVOKED);}public WorkflowHostScan.Page read(Principal who,WorkflowDefinition.Source source,String cursor){reads++;clock.time=clock.time.plusSeconds(1);return new WorkflowHostScan.Page(cursor==null?rows(1,5):rows(6,2),cursor==null?"ids-v1|"+"a".repeat(64)+"|7|5":null,cursor!=null,clock.instant());}});}
 WorkflowHostScheduleService schedules(){return new WorkflowHostScheduleService(new PostgresWorkflowStore(source),runtime(),clock);}
 Command control(Operation op,long expected){return new Command(UUID.randomUUID(),d.id(),1,d.digest(),settings,60,expected,op);}
 @Test void twoScansShareStableEntitiesAndReopenedScheduleAndOriginalReceipt()throws Exception{
  publish();var first=schedules();var c=control(Operation.START,0);var receipt=first.command(p,c,()->null);runtime().tick(p);runtime().tick(p);first.tick(p);var completed=first.status(p,d.id()).schedule();assertEquals(1,completed.completedScans());assertEquals(7,observations());assertEquals(completed,schedules().status(p,d.id()).schedule());assertEquals(receipt,schedules().command(p,c,()->{throw new AssertionError("Original grant must not renew");}));
  clock.time=completed.nextRunAt();schedules().tick(p);runtime().tick(p);runtime().tick(p);schedules().tick(p);assertEquals(14,observations());assertEquals(2,schedules().status(p,d.id()).schedule().completedScans());assertEquals(4,schedules().status(p,d.id()).schedule().sessionBatches());
  var stop=schedules().command(p,control(Operation.STOP,1),()->null);assertEquals("STOPPED",stop.schedule().state());clock.time=clock.time.plusSeconds(600);schedules().tick(p);runtime().tick(p);assertEquals(4,reads);assertEquals(14,observations());assertEquals(receipt,schedules().receipt(p,c.requestId()));
  try(var connection=source.getConnection();var q=connection.prepareStatement("SELECT count(DISTINCT entity_id) FROM inventory.entity_observation WHERE tenant_id=? AND source_instance_id=?")){q.setString(1,tenant.value());q.setString(2,"workflow."+d.id());try(var result=q.executeQuery()){result.next();assertEquals(7,result.getInt(1));}}
 }
 @Test void unknownSurvivesRestartAndExplicitScheduleResumeKeepsOriginalRowsAndTime()throws Exception{
  publish();schedules().command(p,control(Operation.START,0),()->null);failAfterWrite=true;runtime().tick(p);var original=runtime().hostBatches(p,d.id()).getFirst();assertEquals("UNKNOWN",original.state());assertEquals("FAILED",schedules().status(p,d.id()).schedule().state());clock.time=clock.time.plusSeconds(120);schedules().tick(p);runtime().tick(p);assertEquals(1,reads);assertEquals(2,observations());
  failAfterWrite=false;schedules().command(p,control(Operation.RESUME,1),()->null);runtime().tick(p);var confirmed=runtime().hostBatches(p,d.id()).getFirst();assertEquals(original.id(),confirmed.id());assertEquals(original.observedAt(),confirmed.observedAt());assertEquals(original.records(),confirmed.records());assertEquals("CONFIRMED",confirmed.state());assertEquals(1,reads);assertEquals(5,observations());
  schedules().command(p,control(Operation.STOP,2),()->null);clock.time=clock.time.plusSeconds(60);schedules().tick(p);runtime().tick(p);assertEquals(1,reads);
 }
}
