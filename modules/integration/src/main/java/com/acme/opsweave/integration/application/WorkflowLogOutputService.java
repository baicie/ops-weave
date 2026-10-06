package com.acme.opsweave.integration.application;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowLogOutput.*;
import com.acme.opsweave.integration.domain.WorkflowLogOutput.Record;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.identity.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Admission is durable before one external attempt. Unknown batches can only be read and confirmed. */
public final class WorkflowLogOutputService {
 public interface Sink{boolean ready();void write(Batch batch);List<Record> read(Scope scope);}
 public static final class OutputFailure extends RuntimeException{private final boolean unknown;public OutputFailure(boolean unknown){super(unknown?"OUTPUT_UNCONFIRMED":"OUTPUT_REJECTED");this.unknown=unknown;}public boolean unknown(){return unknown;}}
 private final WorkflowStore store;private final WorkflowService workflows;private final Sink sink;private final Clock clock;private final Semaphore capacity;
 public WorkflowLogOutputService(WorkflowStore store,WorkflowService workflows,Sink sink,Clock clock,Semaphore capacity){this.store=store;this.workflows=workflows;this.sink=sink;this.clock=clock;this.capacity=capacity;}
 private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
 private void permission(Principal p,String workflowId,Permission permission){if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"log","workflow."+workflowId),permission).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);}
 public boolean permitted(Principal p,String workflowId,Permission permission){try{permission(p,workflowId,permission);return true;}catch(WorkflowFailure denied){return false;}}
 public boolean available(){if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);try{return sink.ready();}finally{capacity.release();}}
 private Scope scope(Principal p,Receipt receipt){var e=workflows.version(p,receipt.workflowId(),receipt.revision());if(!e.digest().equals(receipt.digest())||!e.definition().target().kind().equals("LOG")||!Set.of("MANUAL_SAMPLE","ZABBIX_LOG").contains(e.definition().source().kind()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);return new Scope(p.tenantId().value(),WorkflowDefinition.hash(List.of(p.subjectId().value())).substring(7),receipt.requestId(),receipt.workflowId(),receipt.revision(),receipt.digest());}
 public Receipt read(Principal p,UUID id){workflows.authorize(p);var value=store.transaction(p.tenantId(),s->s.logOutput(p.subjectId().value(),id).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND)));scope(p,value);return value;}
 public WorkflowService.Page<Receipt> records(Principal p,String id){workflows.authorize(p);WorkflowDefinition.ref(id,1);var rows=store.transaction(p.tenantId(),s->s.logOutputs(p.subjectId().value(),id));var shown=rows.stream().limit(20).toList();shown.forEach(r->scope(p,r));return new WorkflowService.Page<>(shown,rows.size()>20);}
 private boolean matches(Receipt receipt,Scope scope,List<Record> rows){return rows.stream().map(Record::index).toList().equals(receipt.indices())&&WorkflowLogOutput.recordsDigest(scope,rows).equals(receipt.batchDigest());}
 public Data data(Principal p,UUID id){var receipt=read(p,id);permission(p,receipt.workflowId(),Permission.LOG_READ);var scope=scope(p,receipt);if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);try{var rows=sink.read(scope);permission(p,receipt.workflowId(),Permission.LOG_READ);scope(p,receipt);return new Data(id,now(),"clickhouse",receipt.accepted(),matches(receipt,scope,rows),rows);}catch(OutputFailure unavailable){throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}finally{capacity.release();}}
 public Receipt confirm(Principal p,UUID id){var receipt=read(p,id);permission(p,receipt.workflowId(),Permission.LOG_WRITE);if(store.transaction(p.tenantId(),s->s.sampleRecoveryForBatch(p.subjectId().value(),WorkflowSampleRecovery.Kind.LOG_SAMPLE,id).isPresent()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);if(Set.of("CONFIRMED","FAILED").contains(receipt.state()))return receipt;var scope=scope(p,receipt);if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);try{try{var rows=sink.read(scope);permission(p,receipt.workflowId(),Permission.LOG_WRITE);scope(p,receipt);if(matches(receipt,scope,rows))return finish(p,receipt,"CONFIRMED",null);}catch(OutputFailure unknown){/* No write, retry or false-empty confirmation. */}return finish(p,receipt,"UNKNOWN","OUTPUT_UNCONFIRMED");}finally{capacity.release();}}
 private record Prepared(Receipt receipt,Batch batch){}
 public Receipt write(Principal p,Command command){workflows.authorize(p);var prior=store.transaction(p.tenantId(),s->s.logOutput(p.subjectId().value(),command.requestId()));if(prior.isPresent()){prior.get().require(command);scope(p,prior.get());return prior.get();}permission(p,command.id(),Permission.LOG_WRITE);if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
  try{var prepared=store.transaction(p.tenantId(),s->{var owner=p.subjectId().value();var old=s.logOutput(owner,command.requestId());if(old.isPresent()){old.get().require(command);return new Prepared(old.get(),null);}if(s.logOutputCount(owner)>=200)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);var e=workflows.accessibleEntry(p,s,s.published(command.id(),command.revision()).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND)));var d=e.definition();if(!e.digest().equals(command.digest()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);if(!d.target().kind().equals("LOG")||!d.source().kind().equals("MANUAL_SAMPLE")||d.source().configuration()!=null)throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
   var plan=workflows.executionPlan(p,s,e);var run=s.run(owner,command.previewId()).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.PREVIEW_REQUIRED));if(!run.mode().equals("RUN")||!run.workflowId().equals(d.id())||run.revision()!=d.revision()||!run.receipt().digest().equals(d.digest())||!run.receipt().publishable(now())||!run.receipt().origin().equals("MANUAL_SAMPLE")||!run.receipt().inputDigest().equals(WorkflowService.configuredInputDigest(d.source(),command.samples(),null))||run.trace()==null||!run.trace().source().equals(d.source())||!run.trace().target().equals(d.target())||!run.trace().sourceStatus().equals("MANUAL_SAMPLE")||run.trace().missingRaw()!=0||run.trace().truncated())throw new WorkflowFailure(WorkflowFailure.Code.PREVIEW_REQUIRED);
   var evaluated=WorkflowEvaluation.evaluate(plan,command.samples());if(evaluated.rejected()!=0||evaluated.accepted()<1||evaluated.accepted()!=run.receipt().accepted()||evaluated.filtered()!=run.receipt().filtered())throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);var rows=new ArrayList<Record>();for(int i=0;i<evaluated.rows().size();i++)if(evaluated.rows().get(i).status().equals("ACCEPTED")){var row=Record.from(i,evaluated.rows().get(i).steps().getLast().values());var time=Instant.parse(row.eventTime());if(time.isAfter(run.receipt().createdAt())||time.isBefore(run.trace().startedAt().minusSeconds(86400)))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);rows.add(row);}var batch=new Batch(new Scope(p.tenantId().value(),WorkflowDefinition.hash(List.of(owner)).substring(7),command.requestId(),d.id(),d.revision(),d.digest()),rows);var time=now();var receipt=new Receipt(command.requestId(),d.id(),d.revision(),d.digest(),command.previewId(),command.commandDigest(),time,time,"PENDING",evaluated.accepted(),evaluated.filtered(),0,0,evaluated.accepted(),null,batch.digest(),rows.stream().map(Record::index).toList());s.addLogOutput(owner,receipt);return new Prepared(receipt,batch);});
   return attempt(p,prepared);
  }finally{capacity.release();}
 }
 public Receipt write(Principal p,SourceCommand command){
  workflows.authorize(p);var prior=store.transaction(p.tenantId(),s->s.logOutput(p.subjectId().value(),command.requestId()));
  if(prior.isPresent()){prior.get().require(command);scope(p,prior.get());return prior.get();}
  permission(p,command.id(),Permission.LOG_WRITE);if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
  try{
   var entry=workflows.version(p,command.id(),command.revision());
   if(!entry.digest().equals(command.digest()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
   if(!entry.definition().target().kind().equals("LOG")||!entry.definition().source().kind().equals("ZABBIX_LOG"))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
   // The body is read by the server once. A browser cannot supply records, a pin, a URL or an identity.
   var result=workflows.evaluate(p,command.id(),command.revision(),0,command.digest(),true,null,null);
   if(result.receipt().accepted()<1||result.receipt().rejected()!=0||result.missingRaw()!=0||!result.sourceStatus().equals("SUCCEEDED")||!result.receipt().origin().equals("zabbix-jsonrpc"))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
   var prepared=store.transaction(p.tenantId(),s->{
    var owner=p.subjectId().value();var old=s.logOutput(owner,command.requestId());if(old.isPresent()){old.get().require(command);return new Prepared(old.get(),null);}
    if(s.logOutputCount(owner)>=200)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
    var current=workflows.accessibleEntry(p,s,s.published(command.id(),command.revision()).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND)));
    if(!current.digest().equals(command.digest())||!current.definition().equals(entry.definition()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
    workflows.executionPlan(p,s,current);permission(p,command.id(),Permission.LOG_WRITE);
    var run=s.run(owner,result.receipt().id()).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.PREVIEW_REQUIRED));
    if(!run.mode().equals("RUN")||!run.workflowId().equals(command.id())||run.revision()!=command.revision()||!run.receipt().equals(result.receipt())||!run.receipt().publishable(now())||run.trace()==null||!run.trace().source().equals(current.definition().source())||!run.trace().target().equals(current.definition().target())||!run.trace().sourceStatus().equals("SUCCEEDED")||run.trace().missingRaw()!=0)throw new WorkflowFailure(WorkflowFailure.Code.PREVIEW_REQUIRED);
    var rows=new ArrayList<Record>();for(var row:result.evaluation().rows())if(row.status().equals("ACCEPTED")){
     var record=Record.from(row.index(),row.steps().getLast().values());var time=Instant.parse(record.eventTime());
     if(time.isAfter(run.receipt().createdAt())||time.isBefore(run.trace().startedAt().minusSeconds(86400)))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);rows.add(record);
    }
    var batch=new Batch(new Scope(p.tenantId().value(),WorkflowDefinition.hash(List.of(owner)).substring(7),command.requestId(),command.id(),command.revision(),command.digest()),rows);var time=now();
    var receipt=new Receipt(command.requestId(),command.id(),command.revision(),command.digest(),run.receipt().id(),command.commandDigest(),time,time,"PENDING",run.receipt().accepted(),run.receipt().filtered(),0,0,run.receipt().accepted(),null,batch.digest(),rows.stream().map(Record::index).toList());
    s.addLogOutput(owner,receipt);return new Prepared(receipt,batch);
   });
   return attempt(p,prepared);
  }finally{capacity.release();}
 }
 private Receipt attempt(Principal p,Prepared prepared){
   scope(p,prepared.receipt());if(prepared.batch()==null)return prepared.receipt();try{permission(p,prepared.receipt().workflowId(),Permission.LOG_WRITE);store.transaction(p.tenantId(),s->{workflows.executionPlan(p,s,s.published(prepared.receipt().workflowId(),prepared.receipt().revision()).orElseThrow());return null;});}catch(RuntimeException denied){return finish(p,prepared.receipt(),"FAILED",denied instanceof WorkflowFailure f&&f.code()==WorkflowFailure.Code.FORBIDDEN?"FORBIDDEN":"RUNTIME_UNAVAILABLE");}
   boolean wrote=false;try{sink.write(prepared.batch());wrote=true;var rows=sink.read(prepared.batch().scope());permission(p,prepared.receipt().workflowId(),Permission.LOG_WRITE);scope(p,prepared.receipt());if(matches(prepared.receipt(),prepared.batch().scope(),rows))return finish(p,prepared.receipt(),"CONFIRMED",null);return finish(p,prepared.receipt(),"UNKNOWN","OUTPUT_UNCONFIRMED");}catch(OutputFailure failed){return finish(p,prepared.receipt(),wrote||failed.unknown()?"UNKNOWN":"FAILED",wrote?"OUTPUT_UNCONFIRMED":failed.getMessage());}catch(RuntimeException unknown){return finish(p,prepared.receipt(),"UNKNOWN","OUTPUT_UNCONFIRMED");}
 }
 private Receipt finish(Principal p,Receipt original,String state,String error){return store.transaction(p.tenantId(),s->{var current=s.logOutput(p.subjectId().value(),original.requestId()).orElseThrow();if(Set.of("CONFIRMED","FAILED").contains(current.state())||current.state().equals("UNKNOWN")&&state.equals("FAILED"))return current;var time=now();var next=current.finish(state,error,time.isBefore(current.updatedAt())?current.updatedAt():time);s.finishLogOutput(p.subjectId().value(),next);return next;});}
}
