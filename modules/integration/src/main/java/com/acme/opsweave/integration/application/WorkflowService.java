package com.acme.opsweave.integration.application;
import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.api.WorkflowStore.*;
import com.acme.opsweave.integration.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

public final class WorkflowService {
 public interface Models { ModelDefinition resolve(Principal principal,WorkflowDefinition.Target target); }
 public interface Samples { Batch read(Principal principal,WorkflowDefinition.Source source,UUID batchId); }
 public interface Sources { void require(Principal principal,WorkflowDefinition.Source source,Session session,boolean available); default void requireTarget(Principal principal,WorkflowDefinition.Source source,WorkflowDefinition.Target target,Session session,boolean available){if(source.metric()!=null||source.log()!=null)throw error(WorkflowFailure.Code.SOURCE_UNAVAILABLE);} }
 public interface Mappings { MappingDefinition resolve(Principal principal,com.acme.opsweave.telemetry.domain.MetricMappingPin pin); }
 public record Batch(List<Map<String,Object>> records,String origin,long retainedCount,long missingRaw,boolean truncated,String sourceStatus) { public Batch {records=List.copyOf(records); if(!Set.of("fixture","zabbix-jsonrpc").contains(origin)||records.isEmpty()||records.size()>5||retainedCount<records.size()||missingRaw<0||!Set.of("SUCCEEDED","FAILED").contains(sourceStatus)) throw new IllegalArgumentException();} }
 public record Result(Receipt receipt,WorkflowEvaluation evaluation,long retainedCount,long missingRaw,boolean truncated,String sourceStatus) {}
 public record Page<T>(List<T> items,boolean truncated) {}
 public record Comparison(WorkflowComparison.Reference base,WorkflowComparison.Reference candidate,Instant comparedAt,List<WorkflowComparison.Change> changes) { public Comparison { changes=List.copyOf(changes); } }
 public Comparison compare(Principal p,WorkflowComparison.Reference base,WorkflowComparison.Reference candidate){
  if(!base.id().equals(candidate.id()))throw new IllegalArgumentException("Workflow identity differs");
  return transaction(p,s->{var a=comparisonEntry(p,s,base);var b=comparisonEntry(p,s,candidate);return new Comparison(base,candidate,now(),WorkflowComparison.compare(a.definition(),b.definition()));});
 }
 private Entry comparisonEntry(Principal p,Session s,WorkflowComparison.Reference ref){
  var e=accessible(p,s,(ref.state().equals("DRAFT")?s.draft(p.subjectId().value(),ref.id(),ref.revision()):s.published(ref.id(),ref.revision())).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND)));
  if(e.editVersion()!=ref.editVersion()||!e.digest().equals(ref.digest()))throw error(WorkflowFailure.Code.CONFLICT);return e;
 }
 private final WorkflowStore store;private final Models models;private final Samples samples;private final Sources sources;private final Mappings mappings;private final Clock clock;private final Semaphore budget=new Semaphore(2);
 public WorkflowService(WorkflowStore store,Models models,Samples samples,Clock clock) {this(store,models,samples,(p,source,s,available)->{if(source.configuration()!=null)throw error(WorkflowFailure.Code.SOURCE_UNAVAILABLE);},clock);}
 public WorkflowService(WorkflowStore store,Models models,Samples samples,Sources sources,Clock clock) {this(store,models,samples,sources,(p,pin)->{throw error(WorkflowFailure.Code.MAPPING_CHANGED);},clock);}
 public WorkflowService(WorkflowStore store,Models models,Samples samples,Sources sources,Mappings mappings,Clock clock) {this.store=store;this.models=models;this.samples=samples;this.sources=Objects.requireNonNull(sources);this.mappings=Objects.requireNonNull(mappings);this.clock=clock;}
 public void authorize(Principal p) {if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow","*"),Permission.SOURCE_SYNC).denied())throw error(WorkflowFailure.Code.FORBIDDEN);}
 public WorkflowExecutionPlan executionPlan(Principal p,Session session,Entry entry){authorize(p);sources.require(p,entry.definition().source(),session,true);sources.requireTarget(p,entry.definition().source(),entry.definition().target(),session,true);return compile(p,entry.definition());}
 private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
 private <T>T transaction(Principal p,Work<T> work){authorize(p);return store.transaction(p.tenantId(),work);}
 private ModelDefinition outputModel(Principal p,WorkflowDefinition d){var model=d.target().entity()?models.resolve(p,d.target()):null;d.requireOutput(model);return model;}
 private void outputScope(Principal p,WorkflowDefinition.Target t){if(t.mappingPin()!=null&&new Authorizer().decide(p,ResourceRef.metric(p.tenantId(),t.metricKey()),Permission.METRIC_READ).denied())throw error(WorkflowFailure.Code.FORBIDDEN);}
 private WorkflowExecutionPlan compile(Principal p,WorkflowDefinition d){outputScope(p,d.target());return WorkflowOperators.builtIn().compile(d,outputModel(p,d),d.target().mappingPin()==null?null:mappings.resolve(p,d.target().mappingPin()));}
 public Entry save(Principal p,WorkflowDefinition d,Map<String,Position> layout,int expected) {
  authorize(p);compile(p,d);if(expected<0||expected>=1000000)throw new IllegalArgumentException();
  return transaction(p,s->{sources.require(p,d.source(),s,true);sources.requireTarget(p,d.source(),d.target(),s,true);if(s.published(d.id(),d.revision()).isPresent())throw error(WorkflowFailure.Code.CONFLICT);
   var old=s.draft(p.subjectId().value(),d.id(),d.revision());if(old.map(Entry::editVersion).orElse(0)!=expected)throw error(WorkflowFailure.Code.CONFLICT);
   if(old.isEmpty()&&s.drafts(p.subjectId().value()).size()>=50)throw error(WorkflowFailure.Code.CAPACITY);
   var receipt=old.filter(e->e.digest().equals(d.digest())).map(Entry::preview).orElse(null);
   var e=new Entry(d,d.digest(),"DRAFT",expected+1,layout,now(),receipt);s.saveDraft(p.subjectId().value(),e);return e;});
 }
 private Entry accessible(Principal p,Session s,Entry e){sources.require(p,e.definition().source(),s,false);sources.requireTarget(p,e.definition().source(),e.definition().target(),s,false);outputScope(p,e.definition().target());return e;}
 public Entry accessibleEntry(Principal p,Session s,Entry e){authorize(p);if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow",e.definition().id()),Permission.SOURCE_SYNC).denied())throw error(WorkflowFailure.Code.FORBIDDEN);return accessible(p,s,e);}
 public Entry draft(Principal p,String id,int rev){WorkflowDefinition.ref(id,rev);return transaction(p,s->accessible(p,s,s.draft(p.subjectId().value(),id,rev).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND))));}
 public Entry accessibleVersion(Principal p,Session s,String id,int rev){authorize(p);WorkflowDefinition.ref(id,rev);return accessible(p,s,s.published(id,rev).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND)));}
 public Entry version(Principal p,String id,int rev){WorkflowDefinition.ref(id,rev);return transaction(p,s->accessible(p,s,s.published(id,rev).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND))));}
 public Page<Entry> drafts(Principal p){return transaction(p,s->{var rows=s.drafts(p.subjectId().value());rows.forEach(e->accessible(p,s,e));return page(rows);});}
 public Page<Entry> versions(Principal p){return transaction(p,s->{var rows=s.published();rows.forEach(e->accessible(p,s,e));return page(rows);});}
 private Run accessibleRun(Principal p,Session s,Run r){if(r.trace()!=null){sources.require(p,r.trace().source(),s,false);sources.requireTarget(p,r.trace().source(),r.trace().target(),s,false);outputScope(p,r.trace().target());}return r;}
 public Run run(Principal p,UUID id){Objects.requireNonNull(id);return transaction(p,s->accessibleRun(p,s,s.run(p.subjectId().value(),id).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND))));}
 public Page<Run> runs(Principal p){return transaction(p,s->{var rows=s.runs(p.subjectId().value());rows.forEach(r->accessibleRun(p,s,r));return page(rows);});}
 private static <T>Page<T> page(List<T> entries){return new Page<>(List.copyOf(entries.subList(0,Math.min(20,entries.size()))),entries.size()>20);}
 public Entry publish(Principal p,String id,int rev,int edit,String digest,UUID previewId){
  var loaded=draft(p,id,rev);outputModel(p,loaded.definition());WorkflowDefinition.checkDigest(digest);
  return transaction(p,s->{var d=s.draft(p.subjectId().value(),id,rev).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND));
   if(d.editVersion()!=edit||!d.digest().equals(digest))throw error(WorkflowFailure.Code.CONFLICT);
   sources.require(p,d.definition().source(),s,true);sources.requireTarget(p,d.definition().source(),d.definition().target(),s,true);
   if(d.preview()==null||!d.preview().id().equals(previewId))throw error(WorkflowFailure.Code.PREVIEW_REQUIRED);
   var existing=s.published(id,rev);if(existing.isPresent()){if(existing.get().digest().equals(digest))return existing.get();throw error(WorkflowFailure.Code.CONFLICT);}
   compile(p,d.definition());
   WorkflowOperators.builtIn().require(d.definition(),true);
   if(!d.preview().publishable(now()))throw error(WorkflowFailure.Code.PREVIEW_REQUIRED);
   if(d.definition().source().configuration()!=null){var run=s.run(p.subjectId().value(),previewId).orElseThrow(()->error(WorkflowFailure.Code.PREVIEW_REQUIRED));var trace=run.trace();if(!run.receipt().equals(d.preview())||trace==null||!trace.source().equals(d.definition().source())||!trace.sourceStatus().equals("SUCCEEDED")||trace.missingRaw()!=0||!run.receipt().origin().equals("zabbix-jsonrpc"))throw error(WorkflowFailure.Code.PREVIEW_REQUIRED);}
   var all=s.published();if(all.size()>=200)throw error(WorkflowFailure.Code.CAPACITY);
   int latest=all.stream().filter(e->e.definition().id().equals(id)).mapToInt(e->e.definition().revision()).max().orElse(0);if(rev!=latest+1)throw error(WorkflowFailure.Code.CONFLICT);
   var e=new Entry(d.definition(),digest,"PUBLISHED",0,d.layout(),now(),d.preview());s.publish(e,p.subjectId().value());return e;
  });
 }
 public Result evaluate(Principal p,String id,int rev,int edit,String digest,boolean published,List<Map<String,Object>> manual,UUID batchId){
  authorize(p);if(!budget.tryAcquire())throw error(WorkflowFailure.Code.BUSY);
  try {
   var startedAt=now();long started=System.nanoTime();
   var e=published?version(p,id,rev):draft(p,id,rev);if(!e.digest().equals(digest)||e.editVersion()!=edit)throw error(WorkflowFailure.Code.CONFLICT);
   transaction(p,s->{sources.require(p,e.definition().source(),s,true);sources.requireTarget(p,e.definition().source(),e.definition().target(),s,true);return null;});
   var plan=compile(p,e.definition());Batch batch;
   if(e.definition().source().kind().equals("MANUAL_SAMPLE")){if(manual==null||batchId!=null)throw new IllegalArgumentException();batch=null;}
   else {if(manual!=null||(e.definition().source().configuration()==null?batchId==null:batchId!=null))throw new IllegalArgumentException();batch=samples.read(p,e.definition().source(),batchId);}
   if(e.definition().source().configuration()!=null&&!batch.origin().equals("zabbix-jsonrpc"))throw error(WorkflowFailure.Code.INVALID_SAMPLE);
   var values=batch==null?manual:batch.records();var evaluation=WorkflowEvaluation.evaluate(plan,values);
   String input=configuredInputDigest(e.definition().source(),values,batchId);
   var receipt=new Receipt(UUID.randomUUID(),digest,input,batch==null?"MANUAL_SAMPLE":batch.origin(),evaluation.accepted(),evaluation.rejected(),evaluation.filtered(),now());
   var trace=new WorkflowTrace(e.definition().source(),e.definition().target(),batchId,startedAt,Math.max(0,(System.nanoTime()-started)/1000000),batch==null?values.size():batch.retainedCount(),batch==null?0:batch.missingRaw(),batch!=null&&batch.truncated(),batch==null?"MANUAL_SAMPLE":batch.sourceStatus(),true,false,WorkflowTrace.metadata(evaluation));
   transaction(p,s->{var current=(published?s.published(id,rev):s.draft(p.subjectId().value(),id,rev)).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND));
    if(!current.digest().equals(digest)||current.editVersion()!=edit)throw error(WorkflowFailure.Code.CONFLICT);
    sources.require(p,current.definition().source(),s,true);sources.requireTarget(p,current.definition().source(),current.definition().target(),s,true);
    compile(p,current.definition());
    if(s.runs(p.subjectId().value()).size()>=200)throw error(WorkflowFailure.Code.CAPACITY);
    s.addRun(p.subjectId().value(),new Run(id,rev,published?"RUN":"PREVIEW",receipt,trace));
    if(!published)s.saveDraft(p.subjectId().value(),new Entry(current.definition(),digest,"DRAFT",edit,current.layout(),current.updatedAt(),receipt));return null;});
   return new Result(receipt,evaluation,batch==null?values.size():batch.retainedCount(),batch==null?0:batch.missingRaw(),batch!=null&&batch.truncated(),batch==null?"MANUAL_SAMPLE":batch.sourceStatus());
  }finally{budget.release();}
 }
 public static String inputDigest(List<Map<String,Object>> values,UUID batchId){var parts=new ArrayList<String>();parts.add(batchId==null?"MANUAL_SAMPLE":batchId.toString());for(var record:values){parts.add("record");record.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(x->{parts.add(x.getKey());parts.add(x.getValue()==null?"null":x.getValue() instanceof Number?"number":x.getValue() instanceof Boolean?"boolean":"text");parts.add(Objects.toString(x.getValue(),""));});}return WorkflowDefinition.hash(parts);}
 public static String configuredInputDigest(WorkflowDefinition.Source source,List<Map<String,Object>> values,UUID batchId){var digest=inputDigest(values,batchId);var pin=source.configuration();return pin==null?digest:WorkflowDefinition.hash(List.of("registered-source-input-v2",pin.sourceId().toString(),Integer.toString(pin.revision()),pin.digest(),digest));}
 private static WorkflowFailure error(WorkflowFailure.Code c){return new WorkflowFailure(c);}
}
