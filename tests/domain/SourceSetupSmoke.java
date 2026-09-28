import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.catalog.domain.ModelDefinition.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
public final class SourceSetupSmoke {
 static int checks;static void check(boolean ok){checks++;if(!ok)throw new AssertionError("SourceSetup check "+checks);}static void failure(WorkflowFailure.Code code,Runnable r){checks++;try{r.run();}catch(WorkflowFailure e){if(e.code()==code)return;throw new AssertionError(e.code());}throw new AssertionError("Expected "+code);}
 static final String HASH="sha256:"+"a".repeat(64);
 static final ModelDefinition MODEL=new ModelDefinition("custom.setup",1,Kind.ENTITY,"Fixture","fixture",List.of(new Field("name","Name",ModelDefinition.Type.TEXT,true,255,null,null,List.of())),null);
 static Principal user(String tenant,String owner){return new Principal(new SubjectId(owner),new TenantId(tenant),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());}
 static SourceSetupService.Command command(UUID id,String name){return new SourceSetupService.Command(id,name,"Fixture",new Source("MANUAL_SAMPLE","manual"),HASH,new Target(MODEL.id(),1,MODEL.digest()));}
 public static void main(String[] args){
  var store=new InMemoryWorkflowStore();var p=user("setup-a","one");var service=new SourceSetupService(store,(who,t)->MODEL,(who,s)->new SourceSetupService.Connection(HASH,"MANUAL_SAMPLE"),Clock.systemUTC());var c=command(UUID.randomUUID(),"Fixture source");var result=service.confirm(p,c);
  check(result.workflow().definition().id().equals(result.setup().workflowId()));check(result.workflow().definition().nodes().size()==5);check(result.setup().dataMode().equals("MANUAL_SAMPLE"));check(result.workflow().preview()==null);check(result.workflow().state().equals("DRAFT"));check(result.equals(service.confirm(p,c)));check(service.list(p).items().size()==1);check(result.equals(service.read(p,c.requestId())));
  failure(WorkflowFailure.Code.CONFLICT,()->service.confirm(p,command(c.requestId(),"Changed")));check(service.list(user("setup-a","two")).items().isEmpty());check(service.list(user("setup-b","one")).items().isEmpty());failure(WorkflowFailure.Code.NOT_FOUND,()->service.read(user("setup-a","two"),c.requestId()));failure(WorkflowFailure.Code.NOT_FOUND,()->service.read(user("setup-b","one"),c.requestId()));
  var noPermission=new Principal(p.subjectId(),p.tenantId(),Set.of(Permission.ENTITY_READ),p.resourceScope());failure(WorkflowFailure.Code.FORBIDDEN,()->service.confirm(noPermission,c));var narrow=new Principal(p.subjectId(),p.tenantId(),p.permissions(),ResourceScope.of(Set.of(ResourceRef.anyEntity(p.tenantId()))));failure(WorkflowFailure.Code.FORBIDDEN,()->service.list(narrow));
  var changed=new SourceSetupService(store,(who,t)->MODEL,(who,s)->new SourceSetupService.Connection("sha256:"+"b".repeat(64),"MANUAL_SAMPLE"),Clock.systemUTC());failure(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->changed.confirm(p,c));
  var bad=new SourceSetupService.Command(UUID.randomUUID(),"Fixture","",c.source(),HASH,new Target(MODEL.id(),1,HASH));failure(WorkflowFailure.Code.MODEL_CHANGED,()->service.confirm(p,bad));check(service.list(p).items().size()==1);
  var flow=new WorkflowService(store,(who,t)->MODEL,(who,s,b)->{throw new AssertionError("No source call for manual");},Clock.systemUTC());var d=result.workflow().definition();var preview=flow.evaluate(p,d.id(),1,1,d.digest(),false,List.of(Map.of("name"," Fixture ")),null);check(preview.evaluation().accepted()==1);var published=flow.publish(p,d.id(),1,1,d.digest(),preview.receipt().id());check(service.read(p,c.requestId()).workflow().equals(published));check(service.confirm(p,c).workflow().equals(published));
  for(int i=0;i<21;i++)service.confirm(p,command(UUID.randomUUID(),"Fixture "+i));check(service.list(p).items().size()==20&&service.list(p).truncated());
  System.out.println("SourceSetupSmoke: "+checks+" checks passed");
 }
}
