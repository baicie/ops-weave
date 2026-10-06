package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.time.Duration;
import java.util.*;

/** Measured validation metadata only. Remaining input after early abort is unknown, never accepted. */
public final class WorkflowDiagnostics {
    private WorkflowDiagnostics() {}
    public enum SourceFailure { UNAVAILABLE, ACCESS_DENIED, INCOMPLETE_WINDOW, INVALID_RESPONSE, METADATA_CHANGED, SOURCE_KEY_CHANGED, VALUE_TYPE_CHANGED, UNIT_CHANGED, OTHER_FAILURE }
    /** Actual local queue admission and dequeue; source windows are unrelated to this interval. */
    public record Dispatch(UUID id,Instant enqueuedAt,Instant startedAt) {
        public Dispatch {
            Objects.requireNonNull(id);Objects.requireNonNull(enqueuedAt);Objects.requireNonNull(startedAt);
            if(startedAt.isBefore(enqueuedAt)||Duration.between(enqueuedAt,startedAt).compareTo(Duration.ofHours(1))>0)throw new IllegalArgumentException();
        }
        public long waitMillis(){return Duration.between(enqueuedAt,startedAt).toMillis();}
    }
    /** One source-port invocation, independently of record validation and output confirmation. */
    public record SourceRead(int attempts,int completed,int failed,Integer received,SourceFailure failureCode,Instant startedAt,Instant completedAt) {
        public SourceRead {
            Objects.requireNonNull(startedAt);Objects.requireNonNull(completedAt);
            if(attempts!=1||completed<0||completed>1||failed<0||failed>1||completed+failed!=attempts||completedAt.isBefore(startedAt)
                ||completed==1&&(received==null||received<0||received>1000||failureCode!=null)||failed==1&&(received!=null||failureCode==null))throw new IllegalArgumentException();
        }
    }
    public record Node(String nodeId,WorkflowDefinition.Type type,int received,int accepted,int rejected,int filtered,int skipped,Map<String,Integer> issues) {
        public Node {
            if(nodeId==null||!nodeId.matches("[a-z][a-z0-9_-]{0,31}"))throw new IllegalArgumentException();Objects.requireNonNull(type);issues=Map.copyOf(issues);
            for(int n:new int[]{received,accepted,rejected,filtered,skipped})if(n<0||n>1000)throw new IllegalArgumentException();
            if(received!=accepted+rejected+filtered||received+skipped>1000||issues.size()>11||issues.entrySet().stream().anyMatch(e->!WorkflowTrace.ISSUE_CODES.contains(e.getKey())||e.getValue()<1||e.getValue()>rejected))throw new IllegalArgumentException();
        }
    }
    public record Result(String coverage,Void sampleRate,Integer received,Integer accepted,Integer rejected,Integer filtered,Integer unknown,
                         Integer schemaMismatch,Integer missingIdentity,Integer invalidTimestamp,Integer unitMismatch,List<Node> nodes) {
        public Result {
            nodes=List.copyOf(nodes);if(nodes.size()>16||nodes.stream().map(Node::nodeId).distinct().count()!=nodes.size())throw new IllegalArgumentException();
            var counts=Arrays.asList(received,accepted,rejected,filtered,unknown,schemaMismatch,missingIdentity,invalidTimestamp,unitMismatch);
            if(received==null){if(!coverage.equals("UNAVAILABLE")||counts.stream().anyMatch(Objects::nonNull)||!nodes.isEmpty())throw new IllegalArgumentException();}
            else {
                if(counts.subList(0,8).stream().anyMatch(n->n==null||n<0||n>1000)||accepted+rejected+filtered+unknown!=received||schemaMismatch>rejected||missingIdentity>rejected||invalidTimestamp>rejected||unitMismatch!=null&&(unitMismatch<0||unitMismatch>rejected)||!coverage.equals(unknown==0?"COMPLETE":"PARTIAL"))throw new IllegalArgumentException();
                for(var node:nodes)if(node.received()+node.skipped()>received-unknown)throw new IllegalArgumentException();
            }
        }
        public static Result unavailable(){return new Result("UNAVAILABLE",null,null,null,null,null,null,null,null,null,null,List.of());}
    }
    public record Observation(UUID id,WorkflowQuality.Reference reference,WorkflowQuality.Kind kind,long generation,String state,
                              Instant from,Instant till,Instant startedAt,Instant completedAt,Long queueWaitMillis,UUID relatedBatchId,String error,Result result,SourceRead sourceRead,Dispatch dispatch) {
        public Observation(UUID id,WorkflowQuality.Reference reference,WorkflowQuality.Kind kind,long generation,String state,Instant from,Instant till,Instant startedAt,Instant completedAt,Long queueWaitMillis,UUID relatedBatchId,String error,Result result){this(id,reference,kind,generation,state,from,till,startedAt,completedAt,queueWaitMillis,relatedBatchId,error,result,null,null);}
        public Observation(UUID id,WorkflowQuality.Reference reference,WorkflowQuality.Kind kind,long generation,String state,Instant from,Instant till,Instant startedAt,Instant completedAt,Long queueWaitMillis,UUID relatedBatchId,String error,Result result,SourceRead sourceRead){this(id,reference,kind,generation,state,from,till,startedAt,completedAt,queueWaitMillis,relatedBatchId,error,result,sourceRead,null);}
        public Observation {
            Objects.requireNonNull(id);Objects.requireNonNull(reference);Objects.requireNonNull(kind);Objects.requireNonNull(startedAt);Objects.requireNonNull(completedAt);Objects.requireNonNull(result);
            if(dispatch==null?queueWaitMillis!=null:queueWaitMillis==null||queueWaitMillis!=dispatch.waitMillis()||dispatch.startedAt().isAfter(startedAt))throw new IllegalArgumentException();
            if(generation<1||generation>1000001||completedAt.isBefore(startedAt)||!Set.of("CHECKED","REJECTED","SOURCE_FAILED").contains(state)||state.equals("CHECKED")!=(error==null)||error!=null&&!WorkflowQuality.ERRORS.contains(error))throw new IllegalArgumentException();
            if(kind==WorkflowQuality.Kind.HOST_SCAN){if(from!=null||till!=null||result.received()!=null&&result.received()>5)throw new IllegalArgumentException();}
            else if(from==null||from.getNano()!=0||!from.plusSeconds(60).equals(till)||startedAt.isBefore(till.plusSeconds(10))||kind==WorkflowQuality.Kind.METRIC_STREAM&&result.received()!=null&&result.received()>600)throw new IllegalArgumentException();
            if(state.equals("SOURCE_FAILED")?!result.coverage().equals("UNAVAILABLE"):result.received()==null||state.equals("CHECKED")&&(result.rejected()!=0||result.unknown()!=0)||state.equals("REJECTED")&&result.rejected()==0)throw new IllegalArgumentException();
            if(kind!=WorkflowQuality.Kind.METRIC_STREAM&&result.unitMismatch()!=null)throw new IllegalArgumentException();
            if(sourceRead!=null){
                if(sourceRead.startedAt().isBefore(startedAt)||sourceRead.completedAt().isAfter(completedAt)||sourceRead.received()!=null&&sourceRead.received()>(kind==WorkflowQuality.Kind.HOST_SCAN?5:kind==WorkflowQuality.Kind.METRIC_STREAM?600:1000)
                    ||sourceRead.failed()==1&&!state.equals("SOURCE_FAILED")||result.received()!=null&&(sourceRead.completed()!=1||!sourceRead.received().equals(result.received()))
                    ||kind==WorkflowQuality.Kind.HOST_SCAN&&sourceRead.failureCode()!=null&&Set.of(SourceFailure.UNIT_CHANGED,SourceFailure.SOURCE_KEY_CHANGED,SourceFailure.VALUE_TYPE_CHANGED).contains(sourceRead.failureCode()))throw new IllegalArgumentException();
                if(sourceRead.failureCode()!=null&&!Objects.equals(error,switch(sourceRead.failureCode()){case METADATA_CHANGED,SOURCE_KEY_CHANGED,VALUE_TYPE_CHANGED,UNIT_CHANGED->"SOURCE_CHANGED";case UNAVAILABLE->"SOURCE_UNAVAILABLE";case ACCESS_DENIED->error!=null&&Set.of("FORBIDDEN","AUTHORIZATION_EXPIRED","AUTHORIZATION_REVOKED").contains(error)?error:"FORBIDDEN";case INCOMPLETE_WINDOW->"WINDOW_INCOMPLETE";case INVALID_RESPONSE->"INVALID_SAMPLE";case OTHER_FAILURE->error;}))throw new IllegalArgumentException();
            }
        }
    }
    /** Private scope witnesses for asset records; excluded from the public observation. */
    public record Stored(Observation observation,List<String> entityIds) {
        public Stored {Objects.requireNonNull(observation);entityIds=List.copyOf(entityIds);if(entityIds.size()>5||new HashSet<>(entityIds).size()!=entityIds.size()||observation.kind()!=WorkflowQuality.Kind.HOST_SCAN&&!entityIds.isEmpty()||observation.kind()==WorkflowQuality.Kind.HOST_SCAN&&(observation.result().received()!=null&&entityIds.size()!=observation.result().received()||observation.sourceRead()!=null&&observation.sourceRead().received()!=null&&entityIds.size()!=observation.sourceRead().received()))throw new IllegalArgumentException();for(var id:entityIds)if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}
    }
    public record Report(String schemaVersion,Instant asOf,WorkflowQuality.Reference reference,List<Observation> observations,boolean truncated) {
        public Report {Objects.requireNonNull(asOf);Objects.requireNonNull(reference);observations=List.copyOf(observations);if(!schemaVersion.equals("2.0")||observations.size()>20||truncated&&observations.size()!=20||observations.stream().map(Observation::id).distinct().count()!=observations.size())throw new IllegalArgumentException();for(var o:observations)if(!o.reference().equals(reference)||o.completedAt().isAfter(asOf))throw new IllegalArgumentException();}
    }
    public static final class Accumulator {
        private static final Set<String> SCHEMA=Set.of("UNKNOWN_FIELD","MISSING_REQUIRED","NULL_REQUIRED","TOO_LONG","EMPTY_REQUIRED","ENUM_MISMATCH","OUT_OF_RANGE","TYPE_MISMATCH","NUMERIC_LIMIT");
        private final int total;private final Set<String> identities;private final boolean units;private final List<WorkflowDefinition.Node> definition;
        private final Map<String,int[]> counts=new LinkedHashMap<>();private final Map<String,Map<String,Integer>> issues=new HashMap<>();
        private int accepted,rejected,filtered,schema,missing,time,unit;
        public Accumulator(WorkflowDefinition d,int total,Set<String> identities,boolean units){if(total<0||total>1000)throw new IllegalArgumentException();this.total=total;this.identities=Set.copyOf(identities);this.units=units;this.definition=d.nodes();for(var n:definition){counts.put(n.id(),new int[5]);issues.put(n.id(),new HashMap<>());}}
        public void add(WorkflowEvaluation evaluation){for(var row:evaluation.rows()){
            if(accepted+rejected+filtered>=total)throw new IllegalArgumentException();boolean s=false,m=false,t=false,u=false;
            switch(row.status()){case "ACCEPTED"->accepted++;case "REJECTED"->rejected++;case "FILTERED"->filtered++;default->throw new IllegalArgumentException();}
            for(var step:row.steps()){var c=counts.get(step.nodeId());if(c==null)throw new IllegalArgumentException();switch(step.status()){case "OK"->{c[0]++;c[1]++;}case "ERROR"->{c[0]++;c[2]++;}case "FILTERED"->{c[0]++;c[3]++;}case "SKIPPED"->c[4]++;default->throw new IllegalArgumentException();}
                var seen=new HashSet<String>();for(var issue:step.issues()){if(!WorkflowTrace.ISSUE_CODES.contains(issue.code()))throw new IllegalArgumentException();if(seen.add(issue.code()))issues.get(step.nodeId()).merge(issue.code(),1,Integer::sum);s|=SCHEMA.contains(issue.code());m|=identities.contains(issue.field())&&Set.of("MISSING_REQUIRED","NULL_REQUIRED","EMPTY_REQUIRED").contains(issue.code());t|=Set.of("timestamp","eventTime").contains(issue.field());u|=units&&issue.field().equals("unit");}
            }
            if(s)schema++;if(m)missing++;if(t)time++;if(u)unit++;
        }}
        public Result result(){var nodes=definition.stream().map(n->{var c=counts.get(n.id());return new Node(n.id(),n.type(),c[0],c[1],c[2],c[3],c[4],issues.get(n.id()));}).toList();int unknown=total-accepted-rejected-filtered;return new Result(unknown==0?"COMPLETE":"PARTIAL",null,total,accepted,rejected,filtered,unknown,schema,missing,time,units?unit:null,nodes);}
        public Result sourceRejected(boolean timestamp,boolean identity){if(total<1||accepted+rejected+filtered!=0)throw new IllegalArgumentException();rejected=1;schema=1;time=timestamp?1:0;missing=identity?1:0;var source=definition.getFirst();var c=counts.get(source.id());c[0]=1;c[2]=1;issues.get(source.id()).put("TYPE_MISMATCH",1);for(var n:definition)if(n.type()!=WorkflowDefinition.Type.SOURCE)counts.get(n.id())[4]=1;return result();}
        public Result outputRejected(String field,String code){if(accepted<1||!WorkflowTrace.ISSUE_CODES.contains(code))throw new IllegalArgumentException();accepted--;rejected++;schema++;if(Set.of("timestamp","eventTime").contains(field))time++;if(identities.contains(field)&&Set.of("MISSING_REQUIRED","NULL_REQUIRED","EMPTY_REQUIRED").contains(code))missing++;if(units&&field.equals("unit"))unit++;var output=definition.getLast();var c=counts.get(output.id());c[1]--;c[2]++;issues.get(output.id()).merge(code,1,Integer::sum);return result();}
    }
}
