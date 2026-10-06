import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.integration.api.WorkflowStore.Position;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;

public final class WorkflowOutputSmoke {
 static int checks;static void check(boolean ok){checks++;if(!ok)throw new AssertionError("WorkflowOutput check "+checks);}
 static WorkflowDefinition definition(String kind){var nodes=List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("mapping",Type.MAP,"1",TelemetryOutput.fields(kind).stream().collect(java.util.stream.Collectors.toMap(f->f,f->f))),new Node("validate",Type.VALIDATE,"1",Map.of()),new Node("output",Type.OUTPUT,"1",Map.of()));return com.acme.opsweave.integration.domain.WorkflowOperators.builtIn().pin(new WorkflowDefinition("test-"+kind.toLowerCase(),1,"Fixture "+kind,new Source("MANUAL_SAMPLE","manual"),new Target(null,1,null,kind),nodes,List.of(new Edge("source","mapping"),new Edge("mapping","validate"),new Edge("validate","output"))));}
 public static void main(String[] args){
  var p=new Principal(new SubjectId("preview-fixture"),new TenantId("preview-fixture"),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());
  var store=new InMemoryWorkflowStore();var service=new WorkflowService(store,(who,t)->{throw new AssertionError("Telemetry must never resolve entity model");},(who,s,id)->{throw new AssertionError("Manual preview must never read source");},Clock.systemUTC());
  for(String kind:List.of("LOG","METRIC")){
   var d=definition(kind);var layout=new LinkedHashMap<String,Position>();for(var n:d.nodes())layout.put(n.id(),new Position(0,0));
   var draft=service.save(p,d,layout,0);
   Map<String,Object> sample=kind.equals("LOG")?Map.of("body","  fixture body with spaces  ","eventTime","2026-10-01T08:00:00+08:00"):Map.of("metricKey","fixture.cpu","timestamp","2026-10-01T00:00:00Z","value","0.50","metricType","GAUGE");
   var result=service.evaluate(p,d.id(),1,1,d.digest(),false,List.of(sample),null);check(result.evaluation().accepted()==1);check(result.evaluation().dryRun()&&!result.evaluation().writesPerformed());
   var values=result.evaluation().rows().getFirst().steps().getLast().values();check(values.get(kind.equals("LOG")?"eventTime":"timestamp").equals("2026-10-01T00:00:00Z"));
   if(kind.equals("LOG"))check(values.get("body").equals(sample.get("body")));
   var trace=service.run(p,result.receipt().id()).trace();check(trace.target().kind().equals(kind));check(!trace.toString().contains("fixture body with spaces"));
   var published=service.publish(p,d.id(),1,1,d.digest(),result.receipt().id());check(published.state().equals("PUBLISHED"));
   var again=service.evaluate(p,d.id(),1,0,d.digest(),true,List.of(sample),null);check(again.evaluation().accepted()==1);
   var bad=new LinkedHashMap<>(sample);bad.put(kind.equals("LOG")?"eventTime":"timestamp","invalid time");check(WorkflowEvaluation.evaluate(d,null,List.of(bad)).rejected()==1);
   bad=new LinkedHashMap<>(sample);bad.remove(kind.equals("LOG")?"body":"value");check(WorkflowEvaluation.evaluate(d,null,List.of(bad)).rejected()==1);
   if(kind.equals("METRIC")){bad=new LinkedHashMap<>(sample);bad.put("metricType","SUM");check(WorkflowEvaluation.evaluate(d,null,List.of(bad)).rejected()==1);}
   try{new WorkflowDefinition(d.id(),1,d.name(),new Source("ZABBIX_HOST","fixture-host"),d.target(),d.nodes(),d.edges());throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
  }
  var sourceService=new SourceSetupService(store,(who,t)->{throw new AssertionError("Source-only configuration must not resolve a model");},(who,s)->new SourceSetupService.Connection("sha256:"+"a".repeat(64),"MANUAL_SAMPLE"),Clock.systemUTC());
  var c=new SourceSetupService.Command(UUID.randomUUID(),"Fixture source only","",new Source("MANUAL_SAMPLE","manual"),"sha256:"+"a".repeat(64),null);var setup=sourceService.confirm(p,c);
  check(setup.workflow()==null&&setup.setup().initialTarget()==null);check(sourceService.confirm(p,c).equals(setup));check(sourceService.read(p,c.requestId()).equals(setup));
  check(service.drafts(p).items().stream().noneMatch(e->e.definition().id().equals(setup.setup().workflowId())));
  var other=new Principal(new SubjectId("other"),p.tenantId(),p.permissions(),p.resourceScope());try{sourceService.read(other,c.requestId());throw new AssertionError();}catch(WorkflowFailure e){check(e.code()==WorkflowFailure.Code.NOT_FOUND);}
  System.out.println("WorkflowOutputSmoke: "+checks+" checks passed");
 }
}
