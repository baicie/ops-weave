import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.domain.WorkflowComparison.*;
import com.acme.opsweave.integration.application.WorkflowService;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.integration.api.WorkflowStore.*;
import java.util.*;

public final class WorkflowComparisonSmoke {
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError("Comparison "+checks);}
 static void fails(WorkflowFailure.Code code,Runnable task){checks++;try{task.run();}catch(WorkflowFailure e){if(e.code()==code)return;throw e;}throw new AssertionError("Expected "+code);}
 static Reference ref(Entry e){return new Reference(e.definition().id(),e.definition().revision(),e.state(),e.editVersion(),e.digest());}
 static WorkflowDefinition version(WorkflowDefinition d,int revision,String name){return new WorkflowDefinition(d.id(),revision,name,d.source(),d.target(),d.nodes(),d.edges());}
 public static void main(String[] args){
  var base=WorkflowSmoke.flow(WorkflowSmoke.node("trim",Type.TRIM,Map.of()));var next=version(base,2,base.name());
  check(!base.digest().equals(next.digest()));check(WorkflowComparison.compare(base,next).isEmpty());
  var renamed=version(base,2,"Fixture changed name");var changes=WorkflowComparison.compare(base,renamed);check(changes.size()==1);check(changes.getFirst().section()==Section.NAME);check(changes.getFirst().before().equals(base.name()));
  var nodes=new ArrayList<>(base.nodes());var map=nodes.get(1);nodes.set(1,new Node(map.id(),map.type(),map.version(),Map.of("hostname","name","raw_count","count","raw_state","state"),map.operatorDigest()));
  var legacy=nodes.get(2);nodes.set(2,new Node(legacy.id(),legacy.type(),legacy.version(),legacy.config()));
  var mapped=new WorkflowDefinition(base.id(),2,base.name(),base.source(),base.target(),nodes,base.edges());changes=WorkflowComparison.compare(base,mapped);
  check(changes.stream().filter(c->c.section()==Section.MAPPING).count()==2);check(changes.stream().anyMatch(c->c.section()==Section.OPERATOR&&c.before()!=null&&c.after()==null));
  var pin=new ConfigurationPin(UUID.randomUUID(),1,"sha256:"+"a".repeat(64));var remote=new WorkflowDefinition(base.id(),2,base.name(),new Source("ZABBIX_HOST","fixed-source",pin),new Target(base.target().id(),2,"sha256:"+"b".repeat(64)),base.nodes(),base.edges());changes=WorkflowComparison.compare(base,remote);
  check(changes.stream().filter(c->c.section()==Section.SOURCE).count()==5);check(changes.stream().filter(c->c.section()==Section.TARGET).count()==2);
  var extended=WorkflowSmoke.flow(WorkflowSmoke.node("trim",Type.TRIM,Map.of()),WorkflowSmoke.node("default",Type.DEFAULT,Map.of("field","name","value","Fixture fallback")));
  changes=WorkflowComparison.compare(base,extended);check(changes.stream().anyMatch(c->c.section()==Section.NODE&&c.before()==null));check(changes.stream().anyMatch(c->c.section()==Section.PARAMETER&&c.key().equals("value")));check(changes.stream().filter(c->c.section()==Section.EDGE).count()==3);check(changes.stream().anyMatch(c->c.section()==Section.ORDER));
  try{changes.clear();throw new AssertionError();}catch(UnsupportedOperationException e){checks++;}
  var reorderedEdges=new ArrayList<>(base.edges());Collections.reverse(reorderedEdges);var edgeOrder=new WorkflowDefinition(base.id(),2,base.name(),base.source(),base.target(),base.nodes(),reorderedEdges);check(WorkflowComparison.compare(base,edgeOrder).size()==1);check(WorkflowComparison.compare(base,edgeOrder).getFirst().section()==Section.ORDER);
  var store=new InMemoryWorkflowStore();var clock=new WorkflowSmoke.TestClock();var principal=WorkflowSmoke.principal("comparison-fixture","author");
  var service=new WorkflowService(store,(p,t)->{throw new AssertionError("Comparison must not resolve model I/O");},(p,s,id)->{throw new AssertionError("Comparison must not read upstream");},clock);
  var a=new Entry(base,base.digest(),"PUBLISHED",0,WorkflowSmoke.layout(base),clock.instant(),null);var b=new Entry(renamed,renamed.digest(),"DRAFT",1,WorkflowSmoke.layout(renamed),clock.instant(),null);
  store.transaction(principal.tenantId(),s->{s.publish(a,principal.subjectId().value());s.saveDraft(principal.subjectId().value(),b);return null;});
  var report=service.compare(principal,ref(a),ref(b));check(report.changes().size()==1);check(report.base().equals(ref(a)));check(report.comparedAt().equals(clock.instant()));
  check(service.runs(principal).items().isEmpty());check(service.draft(principal,base.id(),2).equals(b));check(service.version(principal,base.id(),1).equals(a));
  fails(WorkflowFailure.Code.CONFLICT,()->service.compare(principal,ref(a),new Reference(base.id(),2,"DRAFT",2,b.digest())));
  fails(WorkflowFailure.Code.NOT_FOUND,()->service.compare(WorkflowSmoke.principal("comparison-fixture","other"),ref(a),ref(b)));
  fails(WorkflowFailure.Code.NOT_FOUND,()->service.compare(WorkflowSmoke.principal("comparison-other-tenant","author"),ref(a),ref(b)));
  var noSync=new com.acme.opsweave.identity.domain.Principal(principal.subjectId(),principal.tenantId(),Set.of(com.acme.opsweave.identity.domain.Permission.ENTITY_READ),com.acme.opsweave.identity.domain.ResourceScope.tenantWide());
  fails(WorkflowFailure.Code.FORBIDDEN,()->service.compare(noSync,ref(a),ref(b)));
  var sourceChecks=new int[1];var scoped=new WorkflowService(store,(p,t)->{throw new AssertionError();},(p,s,id)->{throw new AssertionError();},(p,source,s,available)->{check(!available);sourceChecks[0]++;},clock);
  check(scoped.compare(principal,ref(a),ref(b)).changes().size()==1);check(sourceChecks[0]==2);
  var denied=new WorkflowService(store,(p,t)->{throw new AssertionError();},(p,s,id)->{throw new AssertionError();},(p,source,s,available)->{throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);},clock);
  fails(WorkflowFailure.Code.FORBIDDEN,()->denied.compare(principal,ref(a),ref(b)));
  store.transaction(principal.tenantId(),s->{s.saveDraft(principal.subjectId().value(),new Entry(next,next.digest(),"DRAFT",2,WorkflowSmoke.layout(next),clock.instant(),null));return null;});
  fails(WorkflowFailure.Code.CONFLICT,()->service.compare(principal,ref(a),ref(b)));
  System.out.println("Workflow comparison smoke: "+checks+" checks passed");
 }
}
