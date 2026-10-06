import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class SourceInspectionSmoke {
 static int checks;static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Inspection "+checks);}
 static void failure(WorkflowFailure.Code code,Runnable r){checks++;try{r.run();}catch(WorkflowFailure e){if(e.code()==code)return;throw new AssertionError(e.code());}throw new AssertionError("Expected "+code);}
 static Principal user(String t,String u){return new Principal(new SubjectId(u),new TenantId(t),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());}
 static class Time extends Clock {Instant value=Instant.parse("2026-10-03T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return value;}}
 static final String A="sha256:"+"a".repeat(64),B="sha256:"+"b".repeat(64);
 static SourceInspectionService.Result result(String kind){if(kind.equals("TEST"))return new SourceInspectionService.Result(new SourceInspection.Check(true,"LABELED_FIXTURE",null),null);var f=List.of(new SourceInspection.Field("hostid","TEXT",false));return new SourceInspectionService.Result(null,new SourceInspection.Discovery(f,2,true,"LABELED_FIXTURE","READ_VERIFIED",SourceInspection.fieldDigest(f)));}
 public static void main(String[] args)throws Exception {
  var t=new Time();var p=user("inspection-fixture","owner");var store=new InMemoryWorkflowStore();var config=new AtomicReference<>(new SourceSetupService.Connection(A,"fixture"));SourceSetupService.Connections connections=(who,source)->config.get();
  var setup=new SourceSetupService(store,(who,target)->{throw new AssertionError();},connections,t).confirm(p,new SourceSetupService.Command(UUID.randomUUID(),"Synthetic Host","",new WorkflowDefinition.Source("ZABBIX_HOST","fixture-host"),A,null)).setup();
  var calls=new AtomicInteger();var service=new SourceInspectionService(store,connections,(who,s,k)->{calls.incrementAndGet();return result(k);},t);
  var cmd=new SourceInspectionService.Command(UUID.randomUUID(),1,A);var first=service.run(p,setup.id(),"TEST",cmd);check(first.validity().equals("CURRENT"));check(first.inspection().state().equals("COMPLETED"));check(first.inspection().check().reachable());check(calls.get()==1);check(service.run(p,setup.id(),"TEST",cmd).equals(first));check(calls.get()==1);check(service.read(p,setup.id(),cmd.requestId()).equals(first));
  failure(WorkflowFailure.Code.CONFLICT,()->service.run(p,setup.id(),"DISCOVER",cmd));failure(WorkflowFailure.Code.CONFLICT,()->service.run(p,setup.id(),"TEST",new SourceInspectionService.Command(UUID.randomUUID(),2,A)));check(calls.get()==1);
  var discovery=service.run(p,setup.id(),"DISCOVER",new SourceInspectionService.Command(UUID.randomUUID(),1,A));check(discovery.inspection().discovery().observedRecords()==2);check(discovery.inspection().discovery().complete());check(discovery.inspection().discovery().scope().equals("FIRST_HOST_PAGE"));check(service.recent(p,setup.id()).size()==2);
  failure(WorkflowFailure.Code.NOT_FOUND,()->service.read(user("inspection-fixture","other"),setup.id(),cmd.requestId()));failure(WorkflowFailure.Code.NOT_FOUND,()->service.read(user("other-fixture","owner"),setup.id(),cmd.requestId()));
  var denied=new Principal(p.subjectId(),p.tenantId(),Set.of(),p.resourceScope());failure(WorkflowFailure.Code.FORBIDDEN,()->service.run(denied,setup.id(),"TEST",cmd));check(calls.get()==2);
  t.value=t.value.plusSeconds(901);check(service.read(p,setup.id(),cmd.requestId()).validity().equals("EXPIRED"));check(service.run(p,setup.id(),"TEST",cmd).validity().equals("EXPIRED"));check(calls.get()==2);
  var failed=new SourceInspectionService(store,connections,(who,s,k)->{throw new IllegalStateException("Fixture unavailable");},t);var failedCheck=failed.run(p,setup.id(),"TEST",new SourceInspectionService.Command(UUID.randomUUID(),1,A));check(!failedCheck.inspection().check().reachable());check(failedCheck.validity().equals("UNVERIFIED"));var failedDiscovery=failed.run(p,setup.id(),"DISCOVER",new SourceInspectionService.Command(UUID.randomUUID(),1,A));check(failedDiscovery.inspection().discovery().fields().isEmpty());check(!failedDiscovery.inspection().discovery().complete());
  var currentCmd=new SourceInspectionService.Command(UUID.randomUUID(),1,A);var fresh=service.run(p,setup.id(),"TEST",currentCmd);config.set(new SourceSetupService.Connection(B,"fixture"));check(service.read(p,setup.id(),currentCmd.requestId()).validity().equals("STALE"));failure(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->service.run(p,setup.id(),"TEST",new SourceInspectionService.Command(UUID.randomUUID(),1,A)));
  var instances=new SourceInstanceService(store,connections,t);instances.edit(p,setup.id(),new SourceInstanceService.Edit(UUID.randomUUID(),1,"Rebound synthetic","",B,"ACTIVE"));check(service.read(p,setup.id(),currentCmd.requestId()).validity().equals("STALE"));check(service.run(p,setup.id(),"TEST",currentCmd).inspection().equals(fresh.inspection()));
  var waiting=UUID.randomUUID();store.transaction(p.tenantId(),s->{s.addSourceInspection(p.subjectId().value(),SourceInspection.pending(setup.id(),waiting,"TEST",instances.read(p,setup.id()),t.instant()));return null;});check(service.read(p,setup.id(),waiting).inspection().state().equals("PENDING"));t.value=t.value.plusSeconds(66);check(service.read(p,setup.id(),waiting).inspection().state().equals("UNKNOWN"));check(service.run(p,setup.id(),"TEST",new SourceInspectionService.Command(waiting,2,B)).inspection().state().equals("UNKNOWN"));
  var late=new SourceInspectionService(store,connections,(who,s,k)->{t.value=t.value.plusSeconds(66);return result(k);},t);check(late.run(p,setup.id(),"TEST",new SourceInspectionService.Command(UUID.randomUUID(),2,B)).inspection().state().equals("UNKNOWN"));
  instances.edit(p,setup.id(),new SourceInstanceService.Edit(UUID.randomUUID(),2,"Rebound synthetic","",B,"ARCHIVED"));failure(WorkflowFailure.Code.CONFLICT,()->service.run(p,setup.id(),"TEST",new SourceInspectionService.Command(UUID.randomUUID(),2,B)));
  check(service.recent(p,setup.id()).size()==7);
  instances.edit(p,setup.id(),new SourceInstanceService.Edit(UUID.randomUUID(),3,"Rebound synthetic","",B,"ACTIVE"));var active=instances.read(p,setup.id());int before=calls.get();
  store.transaction(p.tenantId(),s->{for(int n=0;n<2;n++)s.addSourceInspection(p.subjectId().value(),SourceInspection.pending(setup.id(),UUID.randomUUID(),"DISCOVER",active,t.instant()));for(int n=s.sourceInspectionCount(p.subjectId().value());n<200;n++)s.addSourceInspection(p.subjectId().value(),SourceInspection.pending(setup.id(),UUID.randomUUID(),"TEST",active,t.instant()));return null;});check(service.recent(p,setup.id()).size()==20);check(service.recent(p,setup.id(),"TEST").size()==20);check(service.recent(p,setup.id(),"DISCOVER").size()==4);failure(WorkflowFailure.Code.CAPACITY,()->service.run(p,setup.id(),"TEST",new SourceInspectionService.Command(UUID.randomUUID(),active.configurationRevision(),B)));check(calls.get()==before);
  System.out.println("Source inspection smoke: "+checks+" checks passed");
 }
}
