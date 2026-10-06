import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import java.util.*;

public final class WorkflowGraphSmoke {
 static int checks;
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("DAG check "+checks);}
 static void rejects(Runnable code){checks++;try{code.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected graph rejection "+checks);}
 static WorkflowDefinition graph(List<Node> middle,List<Edge> edges){var base=WorkflowSmoke.flow();var nodes=new ArrayList<>(base.nodes().subList(0,2));nodes.addAll(middle);nodes.addAll(base.nodes().subList(2,4));return new WorkflowDefinition(base.id(),1,base.name(),base.source(),base.target(),nodes,edges);}
 static List<Edge> edges(){return List.of(new Edge("source","map"),new Edge("map","left"),new Edge("map","right"),new Edge("left","merge"),new Edge("right","merge"),new Edge("merge","validate"),new Edge("validate","output"));}
 static List<Node> nodes(){return List.of(WorkflowSmoke.node("left",Type.DEFAULT,Map.of("field","count","value","2")),WorkflowSmoke.node("right",Type.DEFAULT,Map.of("field","state","value","on")),WorkflowSmoke.node("merge",Type.MERGE,Map.of()));}
 public static void main(String[] args){
  var d=graph(nodes(),edges());var r=WorkflowEvaluation.evaluate(d,WorkflowSmoke.MODEL,List.of(Map.of("raw_name","Fixture DAG")));
  check(r.accepted()==1);check(r.rows().getFirst().steps().getLast().values().get("state").equals("on"));check(r.rows().getFirst().steps().getLast().values().get("count").toString().equals("2"));check(WorkflowTrace.metadata(r).getFirst().status().equals("ACCEPTED"));
  var conflict=new ArrayList<>(nodes());conflict.set(1,WorkflowSmoke.node("right",Type.DEFAULT,Map.of("field","count","value","3")));
  r=WorkflowEvaluation.evaluate(graph(conflict,edges()),WorkflowSmoke.MODEL,List.of(Map.of("raw_name","Fixture DAG")));
  check(r.rejected()==1);check(r.rows().getFirst().steps().get(4).issues().getFirst().code().equals("MERGE_CONFLICT"));check(WorkflowTrace.metadata(r).getFirst().status().equals("REJECTED"));
  var filtered=new ArrayList<>(nodes());filtered.set(0,WorkflowSmoke.node("left",Type.FILTER,Map.of("field","name","equals","match")));
  r=WorkflowEvaluation.evaluate(graph(filtered,edges()),WorkflowSmoke.MODEL,List.of(Map.of("raw_name","different")));
  check(r.accepted()==1);check(r.rows().getFirst().steps().get(2).status().equals("FILTERED"));check(r.rows().getFirst().steps().get(3).status().equals("OK"));check(WorkflowTrace.metadata(r).getFirst().status().equals("ACCEPTED"));
  filtered.set(1,WorkflowSmoke.node("right",Type.FILTER,Map.of("field","name","equals","match")));
  r=WorkflowEvaluation.evaluate(graph(filtered,edges()),WorkflowSmoke.MODEL,List.of(Map.of("raw_name","different")));check(r.filtered()==1);check(WorkflowTrace.metadata(r).getFirst().status().equals("FILTERED"));
  var cycle=new ArrayList<>(edges());cycle.add(new Edge("merge","left"));rejects(()->graph(nodes(),cycle));
  var duplicate=new ArrayList<>(edges());duplicate.add(edges().get(1));rejects(()->graph(nodes(),duplicate));
  var unknown=new ArrayList<>(edges());unknown.set(1,new Edge("map","missing"));rejects(()->graph(nodes(),unknown));
  var disconnected=new ArrayList<>(edges());disconnected.remove(1);rejects(()->graph(nodes(),disconnected));
  var wrongMerge=new ArrayList<>(nodes());wrongMerge.set(2,WorkflowSmoke.node("merge",Type.TRIM,Map.of()));rejects(()->graph(wrongMerge,edges()));
  check(!d.digest().equals(WorkflowSmoke.flow().digest()));
  System.out.println("WorkflowGraphSmoke: "+checks+" checks passed");
 }
}
