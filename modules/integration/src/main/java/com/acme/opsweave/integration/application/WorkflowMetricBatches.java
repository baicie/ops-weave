package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.telemetry.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Exact published mapping evaluation, shared by bounded background windows. */
public final class WorkflowMetricBatches {
    private WorkflowMetricBatches() {}
    public record Prepared(MetricWriteBatch batch,int inputCount,int filtered) {}
    public static Prepared prepare(Principal p,WorkflowDefinition d,WorkflowExecutionPlan plan,List<Map<String,Object>> rows,Instant from,Instant till) {
        return prepare(p,d,plan,rows,from,till,result->{});
    }
    public static Prepared prepare(Principal p,WorkflowDefinition d,WorkflowExecutionPlan plan,List<Map<String,Object>> rows,Instant from,Instant till,java.util.function.Consumer<WorkflowDiagnostics.Result> observed) {
        if(rows.size()>WorkflowMetricStream.MAX_POINTS)throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
        var labels=new LinkedHashMap<String,String>();labels.put("tenant_id",p.tenantId().value());labels.put("owner_scope",WorkflowDefinition.hash(List.of(p.subjectId().value())).substring(7));
        labels.put("source_instance_id",d.source().instanceId());labels.put("external_item_id",d.source().metric().itemId());labels.put("host_external_id",d.source().metric().hostId());labels.put("metric_key",d.target().metricKey());
        labels.put("unit",plan.metric().mapping().unit());labels.put("mapping_id",d.target().mappingPin().id());labels.put("mapping_revision",Integer.toString(d.target().mappingPin().revision()));labels.put("mapping_digest",d.target().mappingPin().digest());
        labels.put("workflow_id",d.id());labels.put("workflow_revision",Integer.toString(d.revision()));labels.put("workflow_digest",d.digest());labels.put("configuration_digest",d.source().configuration().digest());labels.put("data_mode","zabbix-jsonrpc");labels.put("collection_mode","WINDOW_60S");
        plan.metric().mapping().fixedDimensions().forEach((k,v)->labels.put("dim_"+k,v));
        var diagnostics=new WorkflowDiagnostics.Accumulator(d,rows.size(),Set.of("sourceKey"),true);
        if(rows.isEmpty()){observed.accept(diagnostics.result());return new Prepared(new MetricWriteBatch(labels,List.of(),0),0,0);}
        for(var row:rows){if(!row.keySet().equals(Set.of("timestamp","sourceKey","value"))||!row.values().stream().allMatch(String.class::isInstance)||!d.source().metric().sourceKey().equals(row.get("sourceKey")))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.sourceRejected(false,!row.containsKey("sourceKey")||row.get("sourceKey")==null||"".equals(row.get("sourceKey"))));Instant at;try{at=Instant.parse((String)row.get("timestamp"));}catch(java.time.DateTimeException invalid){throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.sourceRejected(true,false));}if(at.isBefore(from)||!at.isBefore(till))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.sourceRejected(true,false));}
        var points=new ArrayList<MetricPoint>();int filtered=0;
        for(int offset=0;offset<rows.size();offset+=5){var evaluation=WorkflowEvaluation.evaluate(plan,rows.subList(offset,Math.min(rows.size(),offset+5)));diagnostics.add(evaluation);if(evaluation.rejected()!=0)throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.result());filtered+=evaluation.filtered();
        for(var row:evaluation.rows())if(row.status().equals("ACCEPTED")){var values=row.steps().getLast().values();plan.metric().requireNormalized(values);var at=Instant.parse((String)values.get("timestamp"));if(at.isBefore(from)||!at.isBefore(till))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.outputRejected("timestamp","OUT_OF_RANGE"));points.add(new MetricPoint(at,new BigDecimal((String)values.get("value"))));}}
        observed.accept(diagnostics.result());
        return new Prepared(MetricWriteBatch.from(labels,points),rows.size(),filtered);
    }
}
