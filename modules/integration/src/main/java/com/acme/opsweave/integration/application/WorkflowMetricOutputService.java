package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowMetricOutput.*;
import com.acme.opsweave.telemetry.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** One admitted write attempt. Recovery only reads the sink and compares the persisted batch proof. */
public final class WorkflowMetricOutputService {
    public interface Sink {
        void write(MetricWriteBatch batch);
        List<MetricWriteBatch.Sample> read(Map<String,String> labels,List<Long> timestamps);
    }
    public static final class OutputFailure extends RuntimeException {
        private final boolean unknown;
        public OutputFailure(boolean unknown){super(unknown?"OUTPUT_UNCONFIRMED":"OUTPUT_REJECTED");this.unknown=unknown;}
        public boolean unknown(){return unknown;}
    }
    private record Prepared(Receipt receipt,MetricWriteBatch batch) {}
    public record Point(long timestampMillis,String value) {}
    public record Data(UUID requestId,Instant queriedAt,String dataMode,int expectedPoints,boolean proofMatches,List<Point> points) {public Data{points=List.copyOf(points);}}
    private final WorkflowStore store;private final WorkflowService workflows;private final Sink sink;private final Clock clock;
    private final Semaphore capacity;
    public WorkflowMetricOutputService(WorkflowStore store,WorkflowService workflows,Sink sink,Clock clock){this(store,workflows,sink,clock,new Semaphore(2));}
    public WorkflowMetricOutputService(WorkflowStore store,WorkflowService workflows,Sink sink,Clock clock,Semaphore capacity){this.store=store;this.workflows=workflows;this.sink=sink;this.clock=clock;this.capacity=capacity;}
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private void scope(Principal p,Receipt receipt) {
        var entry=workflows.version(p,receipt.workflowId(),receipt.revision());var d=entry.definition();
        if(!entry.digest().equals(receipt.digest())||!p.tenantId().value().equals(receipt.labels().get("tenant_id"))
            ||!WorkflowDefinition.hash(List.of(p.subjectId().value())).substring(7).equals(receipt.labels().get("owner_scope"))
            ||!d.id().equals(receipt.labels().get("workflow_id"))||!d.digest().equals(receipt.labels().get("workflow_digest"))
            ||!d.source().instanceId().equals(receipt.labels().get("source_instance_id"))||!d.target().metricKey().equals(receipt.labels().get("metric_key"))
            ||!d.source().metric().itemId().equals(receipt.labels().get("external_item_id"))||!d.source().metric().hostId().equals(receipt.labels().get("host_external_id"))
            ||!d.source().configuration().digest().equals(receipt.labels().get("configuration_digest"))||!d.target().mappingPin().digest().equals(receipt.labels().get("mapping_digest"))
            ||!d.target().mappingPin().id().equals(receipt.labels().get("mapping_id"))||!Integer.toString(d.target().mappingPin().revision()).equals(receipt.labels().get("mapping_revision")))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
    }
    public Receipt read(Principal p,UUID id) {workflows.authorize(p);var receipt=store.transaction(p.tenantId(),s->s.metricOutput(p.subjectId().value(),id).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND)));scope(p,receipt);return receipt;}
    public WorkflowService.Page<Receipt> records(Principal p,String workflowId) {
        workflows.authorize(p);WorkflowDefinition.ref(workflowId,1);
        var records=store.transaction(p.tenantId(),s->s.metricOutputs(p.subjectId().value(),workflowId));
        var selected=List.copyOf(records.subList(0,Math.min(20,records.size())));selected.forEach(receipt->scope(p,receipt));
        return new WorkflowService.Page<>(selected,records.size()>20);
    }
    public Data data(Principal p,UUID id) {
        var receipt=read(p,id);if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            var points=sink.read(receipt.labels(),receipt.timestamps());var batch=new MetricWriteBatch(receipt.labels(),points,0);
            return new Data(id,now(),"time-series",receipt.timestamps().size(),points.size()==receipt.timestamps().size()&&WorkflowMetricOutput.batchDigest(batch).equals(receipt.batchDigest()),points.stream().map(point->new Point(point.timestampMillis(),point.value().stripTrailingZeros().toPlainString())).toList());
        }catch(RuntimeException unavailable){throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
        finally{capacity.release();}
    }
    public Receipt confirm(Principal p,UUID id) {
        var receipt=read(p,id);if(store.transaction(p.tenantId(),s->s.sampleRecoveryForBatch(p.subjectId().value(),WorkflowSampleRecovery.Kind.METRIC_SAMPLE,id).isPresent()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);if(Set.of("CONFIRMED","FAILED").contains(receipt.state()))return receipt;
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            try {
                var found=sink.read(receipt.labels(),receipt.timestamps());
                var batch=new MetricWriteBatch(receipt.labels(),found,0);
                if(found.size()==receipt.timestamps().size()&&WorkflowMetricOutput.batchDigest(batch).equals(receipt.batchDigest()))return finish(p,receipt,"CONFIRMED",null);
            }catch(RuntimeException unavailable){/* Preserve the unknown range; no write or retry is issued. */}
            return finish(p,receipt,"UNKNOWN","OUTPUT_UNCONFIRMED");
        }finally{capacity.release();}
    }
    public Receipt write(Principal p,Command command) {
        workflows.authorize(p);
        var existing=store.transaction(p.tenantId(),s->s.metricOutput(p.subjectId().value(),command.requestId()));
        if(existing.isPresent()){existing.get().require(command);scope(p,existing.get());return existing.get();}
        if(!capacity.tryAcquire())throw new WorkflowFailure(WorkflowFailure.Code.BUSY);
        try {
            var prepared=store.transaction(p.tenantId(),s->{
                var owner=p.subjectId().value();var prior=s.metricOutput(owner,command.requestId());
                if(prior.isPresent()){prior.get().require(command);return new Prepared(prior.get(),null);}
                if(s.metricOutputCount(owner)>=200)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);
                var e=s.published(command.id(),command.revision()).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));
                if(!e.digest().equals(command.digest()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
                var d=e.definition();
                if(!d.source().kind().equals("ZABBIX_METRIC")||d.source().configuration()==null||d.target().mappingPin()==null)throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
                var plan=workflows.executionPlan(p,s,e);
                var preview=s.run(owner,command.previewId()).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.PREVIEW_REQUIRED));
                if(!preview.mode().equals("RUN")||!preview.workflowId().equals(d.id())||preview.revision()!=d.revision()||!preview.receipt().publishable(now())||!preview.receipt().origin().equals("zabbix-jsonrpc")
                    ||!preview.receipt().digest().equals(d.digest())||preview.trace()==null||!preview.trace().source().equals(d.source())||!preview.trace().target().equals(d.target())
                    ||!preview.trace().sourceStatus().equals("SUCCEEDED")||preview.trace().missingRaw()!=0
                    ||!WorkflowService.configuredInputDigest(d.source(),command.samples(),null).equals(preview.receipt().inputDigest()))throw new WorkflowFailure(WorkflowFailure.Code.PREVIEW_REQUIRED);
                var evaluation=WorkflowEvaluation.evaluate(plan,command.samples());
                if(evaluation.rejected()!=0||evaluation.accepted()==0||evaluation.accepted()!=preview.receipt().accepted()||evaluation.filtered()!=preview.receipt().filtered())throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
                var labels=new LinkedHashMap<String,String>();labels.put("tenant_id",p.tenantId().value());labels.put("owner_scope",WorkflowDefinition.hash(List.of(owner)).substring(7));
                labels.put("source_instance_id",d.source().instanceId());labels.put("external_item_id",d.source().metric().itemId());labels.put("host_external_id",d.source().metric().hostId());
                labels.put("metric_key",d.target().metricKey());labels.put("unit",plan.metric().mapping().unit());labels.put("mapping_id",d.target().mappingPin().id());labels.put("mapping_revision",Integer.toString(d.target().mappingPin().revision()));labels.put("mapping_digest",d.target().mappingPin().digest());
                labels.put("workflow_id",d.id());labels.put("workflow_revision",Integer.toString(d.revision()));labels.put("workflow_digest",d.digest());labels.put("configuration_digest",d.source().configuration().digest());labels.put("data_mode","zabbix-jsonrpc");
                plan.metric().mapping().fixedDimensions().forEach((key,value)->labels.put("dim_"+key,value));
                var points=new ArrayList<MetricPoint>();
                for(var row:evaluation.rows())if(row.status().equals("ACCEPTED")) {
                    var values=row.steps().getLast().values();plan.metric().requireNormalized(values);
                    var timestamp=Instant.parse((String)values.get("timestamp"));
                    if(timestamp.isAfter(preview.receipt().createdAt())||timestamp.isBefore(preview.trace().startedAt().minusSeconds(600)))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
                    points.add(new MetricPoint(timestamp,new BigDecimal((String)values.get("value"))));
                }
                var batch=MetricWriteBatch.from(labels,points);var time=now();
                var receipt=new Receipt(command.requestId(),d.id(),d.revision(),d.digest(),command.previewId(),command.commandDigest(),time,time,"PENDING",evaluation.accepted(),evaluation.filtered(),batch.collapsedPoints(),0,0,batch.samples().size(),null,labels,batch.seriesHash(),WorkflowMetricOutput.batchDigest(batch),batch.samples().stream().map(MetricWriteBatch.Sample::timestampMillis).toList());
                s.addMetricOutput(owner,receipt);return new Prepared(receipt,batch);
            });
            scope(p,prepared.receipt());if(prepared.batch()==null)return prepared.receipt();
            try {
                // Recheck pins and current grants after admission and immediately before the external attempt.
                store.transaction(p.tenantId(),s->{workflows.executionPlan(p,s,s.published(command.id(),command.revision()).orElseThrow());return null;});
            }catch(RuntimeException revoked){return finish(p,prepared.receipt(),"FAILED",revoked instanceof WorkflowFailure failure&&Set.of("FORBIDDEN","SOURCE_CHANGED","MAPPING_CHANGED").contains(failure.code().name())?((WorkflowFailure)revoked).code().name():"RUNTIME_UNAVAILABLE");}
            try{sink.write(prepared.batch());}
            catch(OutputFailure failure){return finish(p,prepared.receipt(),failure.unknown()?"UNKNOWN":"FAILED",failure.getMessage());}
            catch(RuntimeException unknown){return finish(p,prepared.receipt(),"UNKNOWN","OUTPUT_UNCONFIRMED");}
            // The sink's write contract includes an exact value/label readback, not just HTTP acknowledgement.
            return finish(p,prepared.receipt(),"CONFIRMED",null);
        }finally{capacity.release();}
    }
    private Receipt finish(Principal p,Receipt original,String state,String error) {
        return store.transaction(p.tenantId(),s->{var current=s.metricOutput(p.subjectId().value(),original.requestId()).orElseThrow();if(Set.of("CONFIRMED","FAILED").contains(current.state())||current.state().equals("UNKNOWN")&&state.equals("FAILED"))return current;
            var time=now();var result=current.finish(state,error,time.isBefore(current.updatedAt())?current.updatedAt():time);s.finishMetricOutput(p.subjectId().value(),result);return result;});
    }
}
