package com.acme.opsweave.integration.domain;
import com.acme.opsweave.catalog.domain.*;
import java.math.BigDecimal;
import java.util.*;

public record WorkflowEvaluation(List<Row> rows,int accepted,int rejected,int filtered,boolean dryRun,boolean writesPerformed) {
 public record Step(String nodeId,String type,String status,Map<String,Object> values,List<ModelPreview.Issue> issues) {
  public Step { values=Collections.unmodifiableMap(new LinkedHashMap<>(values)); issues=List.copyOf(issues); }
 }
 public record Row(int index,String status,List<Step> steps) { public Row { steps=List.copyOf(steps); } }
 public WorkflowEvaluation { rows=List.copyOf(rows); if(!dryRun || writesPerformed) throw new IllegalArgumentException(); }
 private record Flow(Map<String,Object> values,String state) {}
 public static WorkflowEvaluation evaluate(WorkflowDefinition d,ModelDefinition model,List<Map<String,Object>> samples) {
  return evaluate(WorkflowOperators.builtIn().compile(d,model), samples);
 }
 public static WorkflowEvaluation evaluate(WorkflowExecutionPlan plan,List<Map<String,Object>> samples) {
  var d=plan.definition();var model=plan.model(); if(samples.isEmpty() || samples.size()>5) throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
  var rows=new ArrayList<Row>(); int accepted=0,rejected=0,filtered=0;
  for(int index=0;index<samples.size();index++) {
   bounded(samples.get(index)); var flows=new HashMap<String,Flow>();var steps=new ArrayList<Step>();boolean failed=false;
   for(var n:d.nodes()) {
    var issues=new ArrayList<ModelPreview.Issue>();String stepStatus="OK",state="ACCEPTED";var current=new LinkedHashMap<String,Object>();
    var parents=plan.parents(n.id()).stream().map(flows::get).toList();
    if(n.type()==WorkflowDefinition.Type.SOURCE)current.putAll(samples.get(index));
    else if(parents.stream().anyMatch(parent->parent.state.equals("REJECTED")))state="REJECTED";
    else {
     var live=parents.stream().filter(parent->parent.state.equals("ACCEPTED")).toList();
     if(live.isEmpty())state="FILTERED";
     else for(var parent:live)for(var value:parent.values.entrySet()){
      if(current.containsKey(value.getKey())&&!Objects.equals(current.get(value.getKey()),value.getValue())){if(issues.stream().noneMatch(issue->issue.field().equals(value.getKey())))issues.add(new ModelPreview.Issue(value.getKey(),"MERGE_CONFLICT"));}
      else current.put(value.getKey(),value.getValue());
     }
    }
    if(!state.equals("ACCEPTED")){flows.put(n.id(),new Flow(Map.of(),state));steps.add(new Step(n.id(),n.type().name(),"SKIPPED",Map.of(),List.of()));continue;}
    if(!issues.isEmpty()){failed=true;flows.put(n.id(),new Flow(Map.of(),"REJECTED"));steps.add(new Step(n.id(),n.type().name(),"ERROR",Map.of(),issues));continue;}
    var c=n.config(); var field=c.get("field");
    try { switch(n.type()) {
     case SOURCE,MERGE,OUTPUT -> { }
     case MAP -> { var mapped=new LinkedHashMap<String,Object>(); for(var entry:c.entrySet()) if(current.containsKey(entry.getKey())) mapped.put(entry.getValue(),current.get(entry.getKey())); current=mapped; }
     case TRIM -> current.replaceAll((key,v)->v instanceof String s?s.strip():v);
     case EMPTY_TO_NULL -> { if(current.get(field) instanceof String s && s.isBlank()) current.put(field,null); }
     case DEFAULT -> { if(!current.containsKey(field)) current.put(field,c.get("value")); }
     case ENUM_MAP -> { if(Objects.equals(current.get(field),c.get("from"))) current.put(field,c.get("to")); }
     case SCALE -> { if(current.containsKey(field) && current.get(field)!=null) { Object v=current.get(field); if(!(v instanceof Number) && !(v instanceof String)) throw new IllegalArgumentException(); var scaled=WorkflowDefinition.decimal(v.toString()).multiply(WorkflowDefinition.decimal(c.get("factor"))).stripTrailingZeros(); if(scaled.abs().compareTo(ModelDefinition.MAX_NUMBER)>0 || scaled.scale()>12) throw new IllegalArgumentException(); current.put(field,scaled); } }
     case FILTER -> { if(!current.containsKey(field) || !Objects.equals(current.get(field),c.get("equals"))) { state="FILTERED"; stepStatus="FILTERED"; } }
     case VALIDATE -> { if(d.target().entity()){var result=ModelPreview.evaluate(model,current);current=new LinkedHashMap<>(result.values());issues.addAll(result.issues());}else{var result=plan.metric()==null?TelemetryOutput.validate(d.target().kind(),current):plan.metric().evaluate(current);current=new LinkedHashMap<>(result.values());issues.addAll(result.issues());}if(!issues.isEmpty()){state="REJECTED";stepStatus="ERROR";} }
    } if(plan.metric()!=null&&issues.isEmpty()&&Set.of(WorkflowDefinition.Type.VALIDATE,WorkflowDefinition.Type.OUTPUT).contains(n.type()))plan.metric().requireNormalized(current);else bounded(current); } catch(IllegalArgumentException invalid) { issues.add(new ModelPreview.Issue(field==null?"record":field,"TRANSFORM_FAILED")); state="REJECTED"; stepStatus="ERROR"; }
    if(state.equals("REJECTED"))failed=true;
    flows.put(n.id(),new Flow(current,state));
    steps.add(new Step(n.id(),n.type().name(),stepStatus,current,issues));
   }
   String status=failed?"REJECTED":flows.get(d.nodes().getLast().id()).state;
   rows.add(new Row(index,status,steps)); if(status.equals("ACCEPTED")) accepted++; else if(status.equals("FILTERED")) filtered++; else rejected++;
  }
  return new WorkflowEvaluation(rows,accepted,rejected,filtered,true,false);
 }
 public static void bounded(Map<String,Object> record) {
  if(record.size()>32) throw new IllegalArgumentException("Sample too large"); int size=2;
  for(var e:record.entrySet()) { WorkflowDefinition.field(e.getKey()); var v=e.getValue(); if(v!=null && !(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean) || v instanceof String s && s.length()>2048) throw new IllegalArgumentException("Invalid scalar sample"); if(v instanceof Number n && new BigDecimal(n.toString()).abs().compareTo(ModelDefinition.MAX_NUMBER)>0) throw new IllegalArgumentException("Unsafe sample number"); size+=jsonStringBytes(e.getKey())+2+(v==null?4:v instanceof String text?jsonStringBytes(text):v.toString().length()); }
  if(size>4096) throw new IllegalArgumentException("Sample too large");
 }
 private static int jsonStringBytes(String value) { int bytes=2+value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<32)bytes+=5;else if(c==34||c==92)bytes++;}return bytes; }
}
