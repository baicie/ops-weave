import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.catalog.domain.ModelDefinition.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.telemetry.domain.MetricMappingPin;
import java.util.*;

public final class WorkflowRelationTargetSmoke {
 static int checks;
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Relation target check "+checks);}
 static void rejects(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException|WorkflowFailure e){return;}throw new AssertionError("Expected rejection "+checks);}
 static void failure(WorkflowFailure.Code code,Runnable r){checks++;try{r.run();}catch(WorkflowFailure e){if(e.code()==code)return;throw new AssertionError(e.code());}throw new AssertionError("Expected "+code);}
 static final String DIGEST="sha256:"+"a".repeat(64);
 static final Endpoints ENDPOINTS=new Endpoints(new Ref("builtin.host",1),new Ref("builtin.service",1),Cardinality.MANY_TO_MANY);
 static final ModelDefinition RELATION=new ModelDefinition("custom.contains",1,Kind.RELATION,"Contains","fixture",List.of(),ENDPOINTS);
 static final ModelDefinition ENTITY=new ModelDefinition("custom.node",1,Kind.ENTITY,"Node","fixture",List.of(new Field("name","Name",ModelDefinition.Type.TEXT,true,255,null,null,List.of())),null);
 static Target relationTarget(String from,String to){return new Target(RELATION.id(),1,RELATION.digest(),"RELATION",null,null,from,to);}
 static Node node(String id,WorkflowDefinition.Type type,Map<String,String> c){return WorkflowOperators.builtIn().pin(new Node(id,type,"1",c));}
 static WorkflowDefinition definition(Source source,Target target,Map<String,String> mapping){
  var nodes=List.of(node("source",WorkflowDefinition.Type.SOURCE,Map.of()),node("mapping",WorkflowDefinition.Type.MAP,mapping),node("validate",WorkflowDefinition.Type.VALIDATE,Map.of()),node("output",WorkflowDefinition.Type.OUTPUT,Map.of()));
  var edges=List.of(new Edge("source","mapping"),new Edge("mapping","validate"),new Edge("validate","output"));
  return new WorkflowDefinition("relation-out",1,"Fixture relation output",source,target,nodes,edges);
 }
 static WorkflowDefinition definition(Source source,Target target,Map<String,String> mapping,Node middle){
  var nodes=new ArrayList<Node>();nodes.add(node("source",WorkflowDefinition.Type.SOURCE,Map.of()));nodes.add(node("mapping",WorkflowDefinition.Type.MAP,mapping));nodes.add(middle);nodes.add(node("validate",WorkflowDefinition.Type.VALIDATE,Map.of()));nodes.add(node("output",WorkflowDefinition.Type.OUTPUT,Map.of()));
  var edges=new ArrayList<Edge>();for(int i=0;i<nodes.size()-1;i++)edges.add(new Edge(nodes.get(i).id(),nodes.get(i+1).id()));
  return new WorkflowDefinition("relation-out",1,"Fixture relation output",source,target,nodes,edges);
 }
 static String code(WorkflowEvaluation result,int row){return result.rows().get(row).steps().get(2).issues().getFirst().code();}
 public static void main(String[] args){
  var mapping=Map.of("host_raw","host_identity","svc_raw","interface_identity");
  // RELATION Target: pin + explicit identity fields, closed variants.
  var target=relationTarget("host_identity","interface_identity");
  check(target.relationOutput()&&!target.entity());
  check(target.relation().equals(RELATION.ref()));
  check(target.fromField().equals("host_identity")&&target.toField().equals("interface_identity"));
  rejects(()->target.ref());
  var entity=new Target(ENTITY.id(),1,ENTITY.digest());
  check(entity.entity()&&!entity.relationOutput());
  rejects(()->entity.relation());
  rejects(()->new Target(null,1,null,"RELATION",null,null,"host_identity","interface_identity"));
  rejects(()->new Target(RELATION.id(),1,"sha256:short","RELATION",null,null,"host_identity","interface_identity"));
  rejects(()->new Target(RELATION.id(),0,RELATION.digest(),"RELATION",null,null,"host_identity","interface_identity"));
  rejects(()->new Target(RELATION.id(),1,RELATION.digest(),"RELATION",null,null));
  for(String bad:List.of("HostIdentity","9field","","x".repeat(65),"host-1","host identity","_lead")) {
   rejects(()->new Target(RELATION.id(),1,RELATION.digest(),"RELATION",null,null,bad,"interface_identity"));
   rejects(()->new Target(RELATION.id(),1,RELATION.digest(),"RELATION",null,null,"host_identity",bad));
  }
  rejects(()->new Target(RELATION.id(),1,RELATION.digest(),"RELATION",null,null,"host_identity","host_identity"));
  rejects(()->new Target(RELATION.id(),1,RELATION.digest(),"RELATION",null,null,null,"interface_identity"));
  rejects(()->new Target(RELATION.id(),1,RELATION.digest(),"RELATION",null,null,"host_identity",null));
  var pin=new MetricMappingPin("fixture-mapping",1,DIGEST);
  rejects(()->new Target(RELATION.id(),1,RELATION.digest(),"RELATION",pin,null,"host_identity","interface_identity"));
  rejects(()->new Target(RELATION.id(),1,RELATION.digest(),"RELATION",null,"host.cpu","host_identity","interface_identity"));
  rejects(()->new Target(RELATION.id(),1,RELATION.digest(),"ENTITY",null,null,"host_identity","interface_identity"));
  rejects(()->new Target(null,1,null,"LOG",null,null,"host_identity","interface_identity"));
  rejects(()->new Target(ENTITY.id(),1,ENTITY.digest(),"ENTITY",pin,null,null,null));
  // RELATION definition: node fields stay inside fromField/toField and the digest follows the fields.
  var d=definition(new Source("MANUAL_SAMPLE","manual"),target,mapping);
  check(d.digest().equals(definition(new Source("MANUAL_SAMPLE","manual"),relationTarget("host_identity","interface_identity"),mapping).digest()));
  check(!d.digest().equals(definition(new Source("MANUAL_SAMPLE","manual"),relationTarget("interface_identity","host_identity"),mapping).digest()));
  check(!d.digest().equals(definition(new Source("MANUAL_SAMPLE","manual"),relationTarget("host_id","interface_identity"),Map.of("host_raw","host_id","svc_raw","interface_identity")).digest()));
  rejects(()->definition(new Source("MANUAL_SAMPLE","manual"),target,Map.of("host_raw","host_identity","svc_raw","other_field")));
  rejects(()->definition(new Source("MANUAL_SAMPLE","manual"),target,mapping,node("filter",WorkflowDefinition.Type.FILTER,Map.of("field","other_field","equals","x"))));
  rejects(()->definition(new Source("MANUAL_SAMPLE","manual"),new Target(null,1,null,"LOG"),mapping));
  // ZABBIX_HOST source may write relation output; telemetry output stays blocked.
  check(definition(new Source("ZABBIX_HOST","fixture-host"),target,mapping)!=null);
  rejects(()->definition(new Source("ZABBIX_HOST","fixture-host"),new Target(null,1,null,"METRIC"),mapping));
  // requireOutput matches the published RELATION pin exactly.
  d.requireOutput(RELATION);
  failure(WorkflowFailure.Code.MODEL_CHANGED,()->d.requireOutput(null));
  failure(WorkflowFailure.Code.MODEL_CHANGED,()->d.requireOutput(ENTITY));
  failure(WorkflowFailure.Code.MODEL_CHANGED,()->d.requireOutput(new ModelDefinition(RELATION.id(),1,Kind.RELATION,"Changed","fixture",List.of(),ENDPOINTS)));
  failure(WorkflowFailure.Code.MODEL_CHANGED,()->d.requireOutput(new ModelDefinition(RELATION.id(),2,Kind.RELATION,"Contains","fixture",List.of(),ENDPOINTS)));
  failure(WorkflowFailure.Code.MODEL_CHANGED,()->WorkflowOperators.builtIn().compile(d,new ModelDefinition(RELATION.id(),1,Kind.RELATION,"Changed","fixture",List.of(),ENDPOINTS)));
  // Evaluation validates both explicit source identity fields per row.
  var rows=new ArrayList<Map<String,Object>>();
  var ok=new LinkedHashMap<String,Object>();ok.put("host_raw","host-1");ok.put("svc_raw","svc-1");rows.add(ok);
  var blank=new LinkedHashMap<String,Object>();blank.put("host_raw","   ");blank.put("svc_raw","svc-1");rows.add(blank);
  rows.add(new LinkedHashMap<>(Map.of("host_raw","host-1")));
  rows.add(new LinkedHashMap<>(Map.of("host_raw","x".repeat(257),"svc_raw","svc-1")));
  var result=WorkflowEvaluation.evaluate(d,RELATION,rows);
  check(result.accepted()==1&&result.rejected()==3&&result.filtered()==0&&result.dryRun()&&!result.writesPerformed());
  check(result.rows().get(0).steps().get(2).status().equals("OK")&&result.rows().get(0).steps().get(2).issues().isEmpty());
  check(result.rows().get(0).steps().get(2).values().get("host_identity").equals("host-1")&&result.rows().get(0).steps().get(2).values().get("interface_identity").equals("svc-1"));
  check(code(result,1).equals("EMPTY_REQUIRED")&&result.rows().get(1).steps().get(2).issues().getFirst().field().equals("host_identity"));
  check(code(result,2).equals("MISSING_REQUIRED")&&result.rows().get(2).steps().get(2).issues().getFirst().field().equals("interface_identity"));
  check(code(result,3).equals("TOO_LONG")&&result.rows().get(3).steps().get(2).issues().getFirst().field().equals("host_identity"));
  check(result.rows().get(1).steps().get(3).status().equals("SKIPPED"));
  var nullRow=new LinkedHashMap<String,Object>();nullRow.put("host_raw",null);nullRow.put("svc_raw","svc-1");
  var nulls=WorkflowEvaluation.evaluate(d,RELATION,List.of(nullRow));
  check(nulls.rejected()==1&&code(nulls,0).equals("NULL_REQUIRED"));
  check(WorkflowEvaluation.evaluate(d,RELATION,List.of(Map.of("host_raw","host-1","svc_raw","svc-1"))).accepted()==1);
  System.out.println("WorkflowRelationTargetSmoke: "+checks+" checks passed");
 }
}
