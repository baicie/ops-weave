package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** A complete, bounded source window. Its records live only in memory and the log store. */
public final class WorkflowLogWindow {
    private WorkflowLogWindow() {}
    public static final int WINDOW_SECONDS=60, SETTLE_SECONDS=10, MAX_RECORDS=1000, MAX_HISTORY_REQUESTS=20;

    public static void requireWindow(Instant from,Instant till,Instant now) {
        if(from==null||till==null||now==null||from.getNano()!=0||from.getEpochSecond()<0
            ||!till.equals(from.plusSeconds(WINDOW_SECONDS))||now.isBefore(till.plusSeconds(SETTLE_SECONDS))
            ||from.isBefore(now.minusSeconds(86400)))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
    }

    public record Record(int index,String position,String eventTime,String body,String severityText,String serviceName,String traceId,String spanId) {
        public Record {
            if(index<0||index>=MAX_RECORDS||position==null||!Instant.parse(position).toString().equals(position)
                ||Instant.parse(position).getEpochSecond()<0)throw new IllegalArgumentException();
            // Reuse the normalized LOG validation without widening the five-row sample contract.
            new WorkflowLogOutput.Record(0,eventTime,body,severityText,serviceName,traceId,spanId);
        }
        public static Record from(int index,String position,Map<String,Object> values) {
            return new Record(index,position,(String)values.get("eventTime"),(String)values.get("body"),(String)values.get("severityText"),
                (String)values.get("serviceName"),(String)values.get("traceId"),(String)values.get("spanId"));
        }
    }

    public record Batch(WorkflowLogOutput.Scope scope,Instant from,Instant till,int inputCount,int filtered,int deduplicated,List<Record> records) {
        public Batch(WorkflowLogOutput.Scope scope,Instant from,Instant till,int inputCount,int filtered,List<Record> records){this(scope,from,till,inputCount,filtered,0,records);}
        public Batch {
            Objects.requireNonNull(scope);Objects.requireNonNull(from);records=List.copyOf(records);
            if(from.getNano()!=0||from.getEpochSecond()<0||!from.plusSeconds(WINDOW_SECONDS).equals(till)
                ||inputCount<0||inputCount>MAX_RECORDS||filtered<0||deduplicated<0||filtered+deduplicated+records.size()!=inputCount)throw new IllegalArgumentException();
            int previous=-1;Instant last=null;
            for(var record:records){var position=Instant.parse(record.position());
                if(record.index()<=previous||record.index()>=inputCount||position.isBefore(from)||!position.isBefore(till)
                    ||last!=null&&!position.isAfter(last))throw new IllegalArgumentException();
                previous=record.index();last=position;
            }
        }
        public String digest(){return recordsDigest(scope,from,till,inputCount,filtered,deduplicated,records);}
    }

    public static String recordsDigest(WorkflowLogOutput.Scope scope,Instant from,Instant till,int inputCount,int filtered,List<Record> records) {
        return recordsDigest(scope,from,till,inputCount,filtered,0,records);
    }
    public static String recordsDigest(WorkflowLogOutput.Scope scope,Instant from,Instant till,int inputCount,int filtered,int deduplicated,List<Record> records) {
        var parts=new ArrayList<String>(List.of("workflow-log-window-v1",scope.tenant(),scope.ownerScope(),scope.requestId().toString(),scope.workflowId(),
            Integer.toString(scope.revision()),scope.digest(),from.toString(),till.toString(),Integer.toString(inputCount),Integer.toString(filtered)));
        if(deduplicated!=0){parts.add("deduplicated-v1");parts.add(Integer.toString(deduplicated));}
        for(var row:records){parts.add(Integer.toString(row.index()));parts.add(row.position());parts.add(row.eventTime());parts.add(row.body());
            for(var text:Arrays.asList(row.severityText(),row.serviceName(),row.traceId(),row.spanId())){parts.add(text==null?"null":"text");parts.add(Objects.toString(text,""));}}
        return WorkflowDefinition.hash(parts);
    }

    public static Batch prepare(WorkflowLogOutput.Scope scope,WorkflowExecutionPlan plan,List<Map<String,Object>> samples,Instant from,Instant till,Instant now) {
        return prepare(scope,plan,samples,from,till,now,result->{});
    }
    public static Batch prepare(WorkflowLogOutput.Scope scope,WorkflowExecutionPlan plan,List<Map<String,Object>> samples,Instant from,Instant till,Instant now,java.util.function.Consumer<WorkflowDiagnostics.Result> observed) {
        requireWindow(from,till,now);var d=plan.definition();
        if(!d.source().kind().equals("ZABBIX_LOG")||d.source().log()==null||!d.target().kind().equals("LOG")
            ||!d.id().equals(scope.workflowId())||d.revision()!=scope.revision()||!d.digest().equals(scope.digest())||samples.size()>MAX_RECORDS)
            throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
        var diagnostics=new WorkflowDiagnostics.Accumulator(d,samples.size(),Set.of(),false);
        var positions=new ArrayList<String>();Instant last=null;
        for(var row:samples){
            if(!row.keySet().equals(Set.of("timestamp","body","sourceKey","logEventTime","severityCode","eventSource","eventId"))
                ||!d.source().log().sourceKey().equals(row.get("sourceKey"))||!(row.get("timestamp") instanceof String text))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.sourceRejected(false,false));
            Instant position;try{position=Instant.parse(text);}catch(java.time.DateTimeException invalid){throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.sourceRejected(true,false));}if(!position.toString().equals(text)||position.isBefore(from)||!position.isBefore(till)||last!=null&&!position.isAfter(last))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.sourceRejected(true,false));
            positions.add(text);last=position;
        }
        var records=new ArrayList<Record>();int filtered=0;
        for(int offset=0;offset<samples.size();offset+=5){
            var result=WorkflowEvaluation.evaluate(plan,samples.subList(offset,Math.min(offset+5,samples.size())));
            diagnostics.add(result);
            if(result.rejected()!=0)throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.result());
            filtered+=result.filtered();
            for(var row:result.rows())if(row.status().equals("ACCEPTED")){
                var record=Record.from(offset+row.index(),positions.get(offset+row.index()),row.steps().getLast().values());var event=Instant.parse(record.eventTime());
                if(event.isBefore(now.minusSeconds(86400))||event.isAfter(now))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE,diagnostics.outputRejected("eventTime","OUT_OF_RANGE"));
                records.add(record);
            }
        }
        observed.accept(diagnostics.result());
        return new Batch(scope,from,till,samples.size(),filtered,records);
    }
    public static String inputDigest(WorkflowDefinition.Source source,Instant from,Instant till,List<Map<String,Object>> rows){
        if(!source.kind().equals("ZABBIX_LOG")||source.log()==null||rows.size()>MAX_RECORDS)throw new IllegalArgumentException();
        var parts=new ArrayList<String>(List.of("workflow-log-window-input-v1",source.instanceId(),source.configuration().digest(),source.log().digest(),from.toString(),till.toString()));
        for(var row:rows){WorkflowEvaluation.bounded(row);if(row.values().stream().anyMatch(v->v!=null&&!(v instanceof String)))throw new IllegalArgumentException();parts.add("record");row.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{parts.add(e.getKey());parts.add(e.getValue()==null?"null":"text");parts.add(Objects.toString(e.getValue(),""));});}
        return WorkflowDefinition.hash(parts);
    }
}
