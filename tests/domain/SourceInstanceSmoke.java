import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class SourceInstanceSmoke {
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError("Source instance check "+checks);}
 static void rejects(Runnable work,WorkflowFailure.Code code){try{work.run();throw new AssertionError("Expected rejection");}catch(WorkflowFailure failure){check(failure.code()==code);}}
 static Principal principal(String tenant,String owner){return new Principal(new SubjectId(owner),new TenantId(tenant),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());}
 public static void main(String[] ignored){
  var store=new InMemoryWorkflowStore();var p=principal("source-instance-fixture","one");var clock=Clock.fixed(Instant.parse("2026-10-03T01:00:00Z"),ZoneOffset.UTC);
  var connection=new AtomicReference<>(new SourceSetupService.Connection("sha256:"+"a".repeat(64),"fixture"));
  var setups=new SourceSetupService(store,(who,target)->{throw new AssertionError("No model call");},(who,source)->connection.get(),clock);
  var service=new SourceInstanceService(store,(who,source)->connection.get(),clock);UUID id=UUID.randomUUID();
  var command=new SourceSetupService.Command(id,"Fixture instance","Synthetic metadata",new WorkflowDefinition.Source("ZABBIX_HOST","fixture-source"),connection.get().digest(),null);
  var creation=setups.confirm(p,command);var initial=service.read(p,id);
  check(initial.editVersion()==1&&initial.configurationRevision()==1&&initial.state().equals("ACTIVE"));check(service.list(p).items().equals(List.of(initial)));
  check(service.configurations(p,id).size()==1);check(store.transaction(p.tenantId(),s->s.sourceInstance("one",id).isEmpty()));
  var edit=new SourceInstanceService.Edit(UUID.randomUUID(),1,"Revised instance","Synthetic description",initial.connectionDigest(),"ACTIVE");
  var receipt=service.edit(p,id,edit);check(receipt.instance().editVersion()==2);check(receipt.instance().configurationRevision()==1);
  check(service.edit(p,id,edit).equals(receipt));check(service.receipt(p,id,edit.requestId()).equals(receipt));check(setups.read(p,id).equals(creation));check(service.configurations(p,id).getFirst().createdAt().equals(initial.createdAt()));
  rejects(()->service.edit(p,id,new SourceInstanceService.Edit(edit.requestId(),1,"Changed payload","",initial.connectionDigest(),"ACTIVE")),WorkflowFailure.Code.CONFLICT);
  rejects(()->service.edit(p,id,new SourceInstanceService.Edit(UUID.randomUUID(),1,"Stale writer","",initial.connectionDigest(),"ACTIVE")),WorkflowFailure.Code.CONFLICT);
  check(service.read(p,id).equals(receipt.instance()));
  connection.set(new SourceSetupService.Connection("sha256:"+"b".repeat(64),"fixture"));
  rejects(()->service.edit(p,id,new SourceInstanceService.Edit(UUID.randomUUID(),2,"Wrong pin","","sha256:"+"c".repeat(64),"ACTIVE")),WorkflowFailure.Code.SOURCE_UNAVAILABLE);
  var revised=service.edit(p,id,new SourceInstanceService.Edit(UUID.randomUUID(),2,receipt.instance().name(),receipt.instance().description(),connection.get().digest(),"ACTIVE")).instance();
  check(revised.configurationRevision()==2&&revised.editVersion()==3);var history=service.configurations(p,id);check(history.size()==2&&history.getFirst().revision()==2);check(history.getLast().connectionDigest().equals(initial.connectionDigest()));check(setups.read(p,id).equals(creation));
  var archived=service.edit(p,id,new SourceInstanceService.Edit(UUID.randomUUID(),3,revised.name(),revised.description(),revised.connectionDigest(),"ARCHIVED")).instance();check(archived.state().equals("ARCHIVED"));
  rejects(()->service.edit(p,id,new SourceInstanceService.Edit(UUID.randomUUID(),4,"Archived edit","",archived.connectionDigest(),"ARCHIVED")),WorkflowFailure.Code.CONFLICT);
  var restored=service.edit(p,id,new SourceInstanceService.Edit(UUID.randomUUID(),4,archived.name(),archived.description(),archived.connectionDigest(),"ACTIVE")).instance();check(restored.editVersion()==5&&restored.configurationRevision()==2);
  rejects(()->service.read(principal("source-instance-fixture","two"),id),WorkflowFailure.Code.NOT_FOUND);check(service.list(principal("source-instance-fixture","two")).items().isEmpty());
  rejects(()->service.read(principal("other-tenant","one"),id),WorkflowFailure.Code.NOT_FOUND);
  var denied=new Principal(p.subjectId(),p.tenantId(),Set.of(),p.resourceScope());rejects(()->service.read(denied,id),WorkflowFailure.Code.FORBIDDEN);
  rejects(()->service.receipt(p,id,UUID.randomUUID()),WorkflowFailure.Code.NOT_FOUND);
  try{new SourceInstanceService.Edit(UUID.randomUUID(),0,"Invalid","",initial.connectionDigest(),"ACTIVE");throw new AssertionError();}catch(IllegalArgumentException expected){check(true);}
  try{new SourceInstanceService.Edit(UUID.randomUUID(),1," Trailing ","",initial.connectionDigest(),"ACTIVE");throw new AssertionError();}catch(IllegalArgumentException expected){check(true);}
  check(store.transaction(p.tenantId(),s->s.sourceCommandCount("one"))==4);
  try{store.transaction(p.tenantId(),s->{s.saveSourceInstance("one",initial);throw new IllegalStateException("Fixture rollback");});throw new AssertionError();}catch(IllegalStateException expected){check(service.read(p,id).equals(restored));}
  System.out.println("Source instance smoke: "+checks+" checks passed");
 }
}
