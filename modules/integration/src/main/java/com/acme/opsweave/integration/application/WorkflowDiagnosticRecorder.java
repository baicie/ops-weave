package com.acme.opsweave.integration.application;

import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.*;

/** One bounded metadata observation per source/validation attempt, no point-by-point persistence. */
public final class WorkflowDiagnosticRecorder {
    public record Inspection(Consumer<WorkflowDiagnostics.Result> observer,SourceProbe source) {}
    public static final class SourceProbe {
        private final Clock clock;private final int maximum;private boolean begun;private WorkflowDiagnostics.SourceRead result;
        private SourceProbe(Clock clock,WorkflowQuality.Kind kind){this.clock=clock;maximum=kind==WorkflowQuality.Kind.HOST_SCAN?5:kind==WorkflowQuality.Kind.METRIC_STREAM?600:1000;}
        private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
        public <T>T read(Supplier<T> operation,ToIntFunction<T> records){
            if(begun)throw new IllegalStateException("Duplicate source invocation");begun=true;var started=now();
            try{var value=operation.get();int count=records.applyAsInt(value);if(count<0||count>maximum)throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);result=new WorkflowDiagnostics.SourceRead(1,1,0,count,null,started,now());return value;}
            catch(RuntimeException failure){var safe=failure instanceof IllegalArgumentException?new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE):failure;result=new WorkflowDiagnostics.SourceRead(1,0,1,null,reason(safe),started,now());throw safe;}
        }
        public <T>List<T> read(Supplier<List<T>> operation){return read(operation,List::size);}
        private static WorkflowDiagnostics.SourceFailure reason(RuntimeException failure){
            if(failure instanceof WorkflowFailure f){if(f.sourceFailure()!=null)return f.sourceFailure();return switch(f.code()){case SOURCE_CHANGED->WorkflowDiagnostics.SourceFailure.METADATA_CHANGED;case SOURCE_UNAVAILABLE->WorkflowDiagnostics.SourceFailure.UNAVAILABLE;case FORBIDDEN,AUTHORIZATION_EXPIRED,AUTHORIZATION_REVOKED->WorkflowDiagnostics.SourceFailure.ACCESS_DENIED;case WINDOW_INCOMPLETE->WorkflowDiagnostics.SourceFailure.INCOMPLETE_WINDOW;case INVALID_SAMPLE->WorkflowDiagnostics.SourceFailure.INVALID_RESPONSE;default->WorkflowDiagnostics.SourceFailure.OTHER_FAILURE;};}
            return failure instanceof IllegalArgumentException?WorkflowDiagnostics.SourceFailure.INVALID_RESPONSE:WorkflowDiagnostics.SourceFailure.OTHER_FAILURE;
        }
    }
    private final WorkflowStore store;private final Clock clock;
    public WorkflowDiagnosticRecorder(WorkflowStore store,Clock clock){this.store=store;this.clock=clock;}
    public <T>T inspect(com.acme.opsweave.sharedkernel.TenantId tenant,String owner,WorkflowQuality.Reference ref,WorkflowQuality.Kind kind,long generation,Instant from,Instant till,UUID related,
                        Function<WorkflowStore.Session,Boolean> current,BiFunction<Consumer<WorkflowDiagnostics.Result>,Instant,T> operation){
        return inspect(tenant,owner,ref,kind,generation,from,till,current,()->List.of(),()->related,operation);
    }
    public <T>T inspect(com.acme.opsweave.sharedkernel.TenantId tenant,String owner,WorkflowQuality.Reference ref,WorkflowQuality.Kind kind,long generation,Instant from,Instant till,
                        Function<WorkflowStore.Session,Boolean> current,Supplier<List<String>> entityIds,Supplier<UUID> related,BiFunction<Consumer<WorkflowDiagnostics.Result>,Instant,T> operation){
        return inspectSource(tenant,owner,ref,kind,generation,from,till,current,entityIds,related,(inspection,started)->operation.apply(inspection.observer(),started));
    }
    public <T>T inspectSource(com.acme.opsweave.sharedkernel.TenantId tenant,String owner,WorkflowQuality.Reference ref,WorkflowQuality.Kind kind,long generation,Instant from,Instant till,UUID related,
                        Function<WorkflowStore.Session,Boolean> current,BiFunction<Inspection,Instant,T> operation){return inspectSource(tenant,owner,ref,kind,generation,from,till,current,()->List.of(),()->related,operation);}
    public <T>T inspectSource(com.acme.opsweave.sharedkernel.TenantId tenant,String owner,WorkflowQuality.Reference ref,WorkflowQuality.Kind kind,long generation,Instant from,Instant till,UUID related,
                        Function<WorkflowStore.Session,Boolean> current,WorkflowDiagnostics.Dispatch dispatch,BiFunction<Inspection,Instant,T> operation){return inspectSource(tenant,owner,ref,kind,generation,from,till,current,()->List.of(),()->related,dispatch,operation);}
    public <T>T inspectSource(com.acme.opsweave.sharedkernel.TenantId tenant,String owner,WorkflowQuality.Reference ref,WorkflowQuality.Kind kind,long generation,Instant from,Instant till,
                        Function<WorkflowStore.Session,Boolean> current,Supplier<List<String>> entityIds,Supplier<UUID> related,BiFunction<Inspection,Instant,T> operation){
        return inspectSource(tenant,owner,ref,kind,generation,from,till,current,entityIds,related,null,operation);
    }
    public <T>T inspectSource(com.acme.opsweave.sharedkernel.TenantId tenant,String owner,WorkflowQuality.Reference ref,WorkflowQuality.Kind kind,long generation,Instant from,Instant till,
                        Function<WorkflowStore.Session,Boolean> current,Supplier<List<String>> entityIds,Supplier<UUID> related,WorkflowDiagnostics.Dispatch dispatch,BiFunction<Inspection,Instant,T> operation){
        boolean admitted=store.transaction(tenant,s->{if(!current.apply(s))return false;if(s.diagnosticCount(owner)>=200)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);return true;});
        if(!admitted)throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
        var started=clock.instant().truncatedTo(ChronoUnit.MICROS);var result=new WorkflowDiagnostics.Result[]{null};var probe=new SourceProbe(clock,kind);
        T value;
        try{
            value=operation.apply(new Inspection(v->{if(result[0]!=null)throw new IllegalStateException("Duplicate validation result");result[0]=v;},probe),started);
            if(result[0]==null)throw new IllegalStateException("Missing validation result");
        }catch(RuntimeException failure){
            var measured=probe.result!=null&&probe.result.failed()==1?null:failure instanceof WorkflowFailure f?f.diagnostics():null;
            String code=failure instanceof WorkflowFailure f&&WorkflowQuality.ERRORS.contains(f.code().name())?f.code().name():"RUNTIME_UNAVAILABLE";
            append(tenant,owner,ref,kind,generation,from,till,started,measured==null?WorkflowDiagnostics.Result.unavailable():measured,code,related.get(),entityIds.get(),current,probe.result,dispatch);throw failure;
        }
        append(tenant,owner,ref,kind,generation,from,till,started,result[0],null,related.get(),entityIds.get(),current,probe.result,dispatch);return value;
    }
    private void append(com.acme.opsweave.sharedkernel.TenantId tenant,String owner,WorkflowQuality.Reference ref,WorkflowQuality.Kind kind,long generation,Instant from,Instant till,Instant started,WorkflowDiagnostics.Result result,String error,UUID related,List<String> entityIds,Function<WorkflowStore.Session,Boolean> current,WorkflowDiagnostics.SourceRead sourceRead,WorkflowDiagnostics.Dispatch dispatch){
        var time=clock.instant().truncatedTo(ChronoUnit.MICROS);var observation=new WorkflowDiagnostics.Observation(UUID.randomUUID(),ref,kind,generation,error==null?"CHECKED":result.received()==null?"SOURCE_FAILED":"REJECTED",from,till,started,time,dispatch==null?null:dispatch.waitMillis(),related,error,result,sourceRead,dispatch);
        store.transaction(tenant,s->{if(!current.apply(s))return null;if(s.diagnosticCount(owner)>=200)throw new WorkflowFailure(WorkflowFailure.Code.CAPACITY);s.addDiagnostic(owner,new WorkflowDiagnostics.Stored(observation,entityIds));return null;});
    }
}
