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
 public record Batch(List<Map<String,Object>> records,String origin,long retainedCount,long missingRaw,boolean truncated,String sourceStatus) { public Batch {records=List.copyOf(records); if(!Set.of("fixture","zabbix-jsonrpc").contains(origin)||records.isEmpty()||records.size()>5||retainedCount<records.size()||missingRaw<0||!Set.of("SUCCEEDED","FAILED").contains(sourceStatus)) throw new IllegalArgumentException();} }
 public record Result(Receipt receipt,WorkflowEvaluation evaluation,long retainedCount,long missingRaw,boolean truncated,String sourceStatus) {}
 public record Page<T>(List<T> items,boolean truncated) {}
 private final WorkflowStore store;private final Models models;private final Samples samples;private final Clock clock;private final Semaphore budget=new Semaphore(2);
 public WorkflowService(WorkflowStore store,Models models,Samples samples,Clock clock) {this.store=store;this.models=models;this.samples=samples;this.clock=clock;}
 public void authorize(Principal p) {if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow","*"),Permission.SOURCE_SYNC).denied())throw error(WorkflowFailure.Code.FORBIDDEN);}
 private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
 private <T>T transaction(Principal p,Work<T> work){authorize(p);return store.transaction(p.tenantId(),work);}
 public Entry save(Principal p,WorkflowDefinition d,Map<String,Position> layout,int expected) {
  authorize(p);d.requireModel(models.resolve(p,d.target()));if(expected<0||expected>=1000000)throw new IllegalArgumentException();
  return transaction(p,s->{if(s.published(d.id(),d.revision()).isPresent())throw error(WorkflowFailure.Code.CONFLICT);
   var old=s.draft(p.subjectId().value(),d.id(),d.revision());if(old.map(Entry::editVersion).orElse(0)!=expected)throw error(WorkflowFailure.Code.CONFLICT);
   if(old.isEmpty()&&s.drafts(p.subjectId().value()).size()>=50)throw error(WorkflowFailure.Code.CAPACITY);
   var receipt=old.filter(e->e.digest().equals(d.digest())).map(Entry::preview).orElse(null);
   var e=new Entry(d,d.digest(),"DRAFT",expected+1,layout,now(),receipt);s.saveDraft(p.subjectId().value(),e);return e;});
 }
 public Entry draft(Principal p,String id,int rev){WorkflowDefinition.ref(id,rev);return transaction(p,s->s.draft(p.subjectId().value(),id,rev).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND)));}
 public Entry version(Principal p,String id,int rev){WorkflowDefinition.ref(id,rev);return transaction(p,s->s.published(id,rev).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND)));}
 public Page<Entry> drafts(Principal p){return transaction(p,s->page(s.drafts(p.subjectId().value())));}
 public Page<Entry> versions(Principal p){return transaction(p,s->page(s.published()));}
 public Run run(Principal p,UUID id){Objects.requireNonNull(id);return transaction(p,s->s.run(p.subjectId().value(),id).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND)));}
 public Page<Run> runs(Principal p){return transaction(p,s->page(s.runs(p.subjectId().value())));}
 private static <T>Page<T> page(List<T> entries){return new Page<>(List.copyOf(entries.subList(0,Math.min(20,entries.size()))),entries.size()>20);}
 public Entry publish(Principal p,String id,int rev,int edit,String digest,UUID previewId){
  var loaded=draft(p,id,rev);loaded.definition().requireModel(models.resolve(p,loaded.definition().target()));WorkflowDefinition.checkDigest(digest);
  return transaction(p,s->{var d=s.draft(p.subjectId().value(),id,rev).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND));
   if(d.editVersion()!=edit||!d.digest().equals(digest))throw error(WorkflowFailure.Code.CONFLICT);
   if(d.preview()==null||!d.preview().id().equals(previewId))throw error(WorkflowFailure.Code.PREVIEW_REQUIRED);
   var existing=s.published(id,rev);if(existing.isPresent()){if(existing.get().digest().equals(digest))return existing.get();throw error(WorkflowFailure.Code.CONFLICT);}
   if(!d.preview().publishable(now()))throw error(WorkflowFailure.Code.PREVIEW_REQUIRED);
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
   var model=models.resolve(p,e.definition().target());Batch batch;
   if(e.definition().source().kind().equals("MANUAL_SAMPLE")){if(manual==null||batchId!=null)throw new IllegalArgumentException();batch=null;}
   else {if(manual!=null||batchId==null)throw new IllegalArgumentException();batch=samples.read(p,e.definition().source(),batchId);}
   var values=batch==null?manual:batch.records();var evaluation=WorkflowEvaluation.evaluate(e.definition(),model,values);
   var parts=new ArrayList<String>();parts.add(batchId==null?"MANUAL_SAMPLE":batchId.toString());
   for(var record:values){parts.add("record");record.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(x->{parts.add(x.getKey());parts.add(x.getValue()==null?"null":x.getValue() instanceof Number?"number":x.getValue() instanceof Boolean?"boolean":"text");parts.add(Objects.toString(x.getValue(),""));});}
   var receipt=new Receipt(UUID.randomUUID(),digest,WorkflowDefinition.hash(parts),batch==null?"MANUAL_SAMPLE":batch.origin(),evaluation.accepted(),evaluation.rejected(),evaluation.filtered(),now());
   var trace=new WorkflowTrace(e.definition().source(),e.definition().target(),batchId,startedAt,Math.max(0,(System.nanoTime()-started)/1000000),batch==null?values.size():batch.retainedCount(),batch==null?0:batch.missingRaw(),batch!=null&&batch.truncated(),batch==null?"MANUAL_SAMPLE":batch.sourceStatus(),true,false,WorkflowTrace.metadata(evaluation));
   transaction(p,s->{var current=(published?s.published(id,rev):s.draft(p.subjectId().value(),id,rev)).orElseThrow(()->error(WorkflowFailure.Code.NOT_FOUND));
    if(!current.digest().equals(digest)||current.editVersion()!=edit)throw error(WorkflowFailure.Code.CONFLICT);
    if(s.runs(p.subjectId().value()).size()>=200)throw error(WorkflowFailure.Code.CAPACITY);
    s.addRun(p.subjectId().value(),new Run(id,rev,published?"RUN":"PREVIEW",receipt,trace));
    if(!published)s.saveDraft(p.subjectId().value(),new Entry(current.definition(),digest,"DRAFT",edit,current.layout(),current.updatedAt(),receipt));return null;});
   return new Result(receipt,evaluation,batch==null?values.size():batch.retainedCount(),batch==null?0:batch.missingRaw(),batch!=null&&batch.truncated(),batch==null?"MANUAL_SAMPLE":batch.sourceStatus());
  }finally{budget.release();}
 }
 private static WorkflowFailure error(WorkflowFailure.Code c){return new WorkflowFailure(c);}
}
