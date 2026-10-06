import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.WorkflowService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.telemetry.domain.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Explicit synthetic input; no history, source or business output is exercised here. */
public final class WorkflowStandardMetricSmoke {
 static int checks;
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Standard metric check "+checks);}
 static void rejects(Runnable work){checks++;try{work.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid value "+checks);}
 static void fails(WorkflowFailure.Code code,Runnable work){checks++;try{work.run();}catch(WorkflowFailure expected){if(expected.code()==code)return;throw new AssertionError(expected.code());}throw new AssertionError("Expected "+code);}
 static MappingDefinition changed(MappingDefinition m,String key,int revision,MetricType type,MetricValueType values,List<String> names,Map<String,String> dimensions,String transform,BigDecimal max){
  return new MappingDefinition(m.id(),m.connector(),m.itemKeyExact(),key,m.displayName(),type,m.unit(),values,names,dimensions,transform,revision,m.minimum(),max);
 }
 static WorkflowDefinition flow(String id,MappingDefinition m){
  return WorkflowOperators.builtIn().pin(new WorkflowDefinition(id,1,"Fixture standard metric",new Source("MANUAL_SAMPLE","manual"),new Target(null,1,null,"METRIC",m.pin(),m.metricKey()),List.of(new Node("source",Type.SOURCE,"1",Map.of()),new Node("mapping",Type.MAP,"1",Map.of("timestamp","timestamp","value","value","sourceKey","sourceKey")),new Node("validate",Type.VALIDATE,"1",Map.of()),new Node("output",Type.OUTPUT,"1",Map.of())),List.of(new Edge("source","mapping"),new Edge("mapping","validate"),new Edge("validate","output"))));
 }
 static Map<String,Object> sample(MappingDefinition m,Object value){return Map.of("timestamp","2026-10-04T08:00:00+08:00","sourceKey",m.itemKeyExact(),"value",value);}
 public static void main(String[] args)throws Exception{
  var m=MappingDocumentParser.parse(Files.readString(Path.of("extensions/mappings/zabbix-cpu-user.yaml")));
  check(m.pin().digest().equals("sha256:99a6d3d6b549bd47da048352fd902f2eb4a3ca54a4c6c8824be20bef33877247"));
  var d=flow("standard-fixture",m);var plan=WorkflowOperators.builtIn().compile(d,null,m);var input=sample(m,"12.5");
  var result=WorkflowEvaluation.evaluate(plan,List.of(input));check(result.accepted()==1&&result.dryRun()&&!result.writesPerformed());
  var normalized=result.rows().getFirst().steps().getLast().values();
  check(normalized.keySet().equals(Set.of("timestamp","metricKey","value","unit","metricType","dimensions","mappingPin")));
  check(normalized.get("timestamp").equals("2026-10-04T00:00:00Z"));check(normalized.get("metricKey").equals(m.metricKey()));check(normalized.get("value").equals("0.125"));check(normalized.get("unit").equals("1"));check(normalized.get("metricType").equals("GAUGE"));check(normalized.get("dimensions").equals(Map.of("mode","user")));check(normalized.get("mappingPin").equals(Map.of("id",m.id(),"revision",1,"digest",m.pin().digest())));
  check(result.rows().getFirst().steps().get(1).values().get("value").equals("12.5"));check(result.rows().getFirst().steps().get(2).values().equals(normalized));
  for(Object value:List.of("-1","100.1","NaN","Infinity","true","1e309","0.00000000000000000000000000000000000000000000000000000000000000001"))check(WorkflowEvaluation.evaluate(plan,List.of(sample(m,value))).rejected()==1);
  for(String key:List.of("sourceKey","timestamp","value")){
   var bad=new HashMap<>(input);bad.remove(key);check(WorkflowEvaluation.evaluate(plan,List.of(bad)).rejected()==1);
   bad.put(key,null);check(WorkflowEvaluation.evaluate(plan,List.of(bad)).rejected()==1);
  }
  var wrong=new HashMap<>(input);wrong.put("sourceKey","system.cpu.util[,idle]");var wrongResult=WorkflowEvaluation.evaluate(plan,List.of(wrong));check(wrongResult.rejected()==1);check(wrongResult.rows().getFirst().steps().get(2).issues().getFirst().field().equals("sourceKey"));
  wrong.put("sourceKey",m.itemKeyExact());wrong.put("timestamp","bad time");check(WorkflowEvaluation.evaluate(plan,List.of(wrong)).rejected()==1);
  rejects(()->WorkflowEvaluation.evaluate(plan,List.of(sample(m,Map.of("value",12.5)))));
  for(String field:List.of("unit","metricType","metricKey","dimensions","mappingPin")){
   var forged=new HashMap<>(normalized);forged.put(field,"forged");rejects(()->plan.metric().requireNormalized(forged));
  }
  for(String value:List.of("2","-0.1","NaN","1e0","", "1".repeat(65))){var forged=new HashMap<>(normalized);forged.put("value",value);rejects(()->plan.metric().requireNormalized(forged));}
  var forged=new HashMap<>(normalized);forged.put("timestamp","bad time");rejects(()->plan.metric().requireNormalized(forged));
  try{((Map<?,?>)normalized.get("dimensions")).clear();throw new AssertionError("Expected immutable dimensions");}catch(UnsupportedOperationException expected){checks++;}
  var integer=changed(m,m.metricKey(),2,MetricType.GAUGE,MetricValueType.INTEGER,m.dimensionSchema(),m.fixedDimensions(),"identity",new BigDecimal("100"));
  var integerPlan=WorkflowOperators.builtIn().compile(flow("integer-fixture",integer),null,integer);check(WorkflowEvaluation.evaluate(integerPlan,List.of(sample(integer,"10"))).accepted()==1);check(WorkflowEvaluation.evaluate(integerPlan,List.of(sample(integer,"10.5"))).rejected()==1);
  fails(WorkflowFailure.Code.MAPPING_CHANGED,()->WorkflowOperators.builtIn().compile(d,null));
  var revised=changed(m,m.metricKey(),2,m.metricType(),m.valueType(),m.dimensionSchema(),m.fixedDimensions(),m.valueTransform(),m.maximum());
  var changedRange=changed(m,m.metricKey(),1,m.metricType(),m.valueType(),m.dimensionSchema(),m.fixedDimensions(),m.valueTransform(),new BigDecimal("2"));
  for(var mismatch:List.of(revised,changedRange,changed(m,"host.other",1,m.metricType(),m.valueType(),m.dimensionSchema(),m.fixedDimensions(),m.valueTransform(),m.maximum())))fails(WorkflowFailure.Code.MAPPING_CHANGED,()->WorkflowOperators.builtIn().compile(d,null,mismatch));
  for(var unsupported:List.of(changed(m,m.metricKey(),2,MetricType.SUM,m.valueType(),m.dimensionSchema(),m.fixedDimensions(),m.valueTransform(),m.maximum()),changed(m,m.metricKey(),2,m.metricType(),MetricValueType.STRING,m.dimensionSchema(),m.fixedDimensions(),m.valueTransform(),m.maximum()),changed(m,m.metricKey(),2,m.metricType(),m.valueType(),List.of("mode","host"),m.fixedDimensions(),m.valueTransform(),m.maximum()),changed(m,m.metricKey(),2,m.metricType(),m.valueType(),m.dimensionSchema(),m.fixedDimensions(),"unsupported",m.maximum())))fails(WorkflowFailure.Code.MAPPING_CHANGED,()->WorkflowOperators.builtIn().compile(flow("unsupported-fixture",unsupported),null,unsupported));
  rejects(()->new WorkflowDefinition(d.id(),1,d.name(),new Source("ZABBIX_HOST","fixture-source"),d.target(),d.nodes(),d.edges()));
  var store=new InMemoryWorkflowStore();var available=new AtomicReference<MappingDefinition>(m);var race=new AtomicBoolean();
  var service=new WorkflowService(store,(p,t)->{throw new AssertionError("No entity model for metrics");},(p,s,id)->{throw new AssertionError("Manual samples never read upstream");},(p,s,session,live)->{},(p,pin)->{var mapping=available.get();if(race.getAndSet(false))available.set(changedRange);return mapping;},Clock.systemUTC());
  var principal=new Principal(new SubjectId("standard-author"),new com.acme.opsweave.sharedkernel.TenantId("standard-fixture"),Set.of(Permission.SOURCE_SYNC,Permission.METRIC_READ),ResourceScope.tenantWide());
  var noMetric=new Principal(principal.subjectId(),principal.tenantId(),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());
  var denied=new Principal(principal.subjectId(),principal.tenantId(),principal.permissions(),ResourceScope.of(Set.of(new ResourceRef(principal.tenantId(),"workflow","*"),ResourceRef.metric(principal.tenantId(),"host.other"))));
  fails(WorkflowFailure.Code.FORBIDDEN,()->service.save(noMetric,d,WorkflowSmoke.layout(d),0));fails(WorkflowFailure.Code.FORBIDDEN,()->service.save(denied,d,WorkflowSmoke.layout(d),0));check(service.drafts(principal).items().isEmpty());
  check(service.save(principal,d,WorkflowSmoke.layout(d),0).editVersion()==1);fails(WorkflowFailure.Code.FORBIDDEN,()->service.draft(denied,d.id(),1));
  var receipt=service.evaluate(principal,d.id(),1,1,d.digest(),false,List.of(input),null).receipt();check(receipt.accepted()==1);check(service.run(principal,receipt.id()).trace().target().mappingPin().equals(m.pin()));check(!service.run(principal,receipt.id()).trace().toString().contains("0.125"));
  var published=service.publish(principal,d.id(),1,1,d.digest(),receipt.id());check(published.definition().target().mappingPin().equals(m.pin()));
  available.set(null);check(service.version(principal,d.id(),1).equals(published));check(service.publish(principal,d.id(),1,1,d.digest(),receipt.id()).equals(published));fails(WorkflowFailure.Code.MAPPING_CHANGED,()->service.evaluate(principal,d.id(),1,0,d.digest(),true,List.of(input),null));fails(WorkflowFailure.Code.FORBIDDEN,()->service.version(denied,d.id(),1));
  var other=new Principal(new SubjectId("other"),principal.tenantId(),principal.permissions(),principal.resourceScope());fails(WorkflowFailure.Code.NOT_FOUND,()->service.run(other,receipt.id()));
  available.set(m);var racing=flow("racing-fixture",m);service.save(principal,racing,WorkflowSmoke.layout(racing),0);int count=service.runs(principal).items().size();race.set(true);fails(WorkflowFailure.Code.MAPPING_CHANGED,()->service.evaluate(principal,racing.id(),1,1,racing.digest(),false,List.of(input),null));check(service.runs(principal).items().size()==count);check(service.draft(principal,racing.id(),1).preview()==null);
  var newFlow=flow(d.id(),revised);check(!newFlow.digest().equals(d.digest()));var diff=WorkflowComparison.compare(d,newFlow);check(diff.stream().anyMatch(c->c.key().equals("mappingRevision")&&c.after().equals("2")));check(diff.stream().anyMatch(c->c.key().equals("mappingDigest")));
  check(!flow(d.id(),changedRange).digest().equals(d.digest()));
  System.out.println("WorkflowStandardMetricSmoke: "+checks+" checks passed");
 }
}
