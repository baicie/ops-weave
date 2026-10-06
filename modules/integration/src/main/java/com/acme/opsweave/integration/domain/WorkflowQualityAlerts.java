package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** User configured metadata thresholds. No notification, action, source or output port. */
public final class WorkflowQualityAlerts {
    private WorkflowQualityAlerts() {}
    public enum Kind { SOURCE_FAILURES, OUTPUT_REJECTIONS, QUEUE_WAIT, TASK_FAILURE }
    public enum State { TRIGGERED, NORMAL, UNAVAILABLE }
    public static final Set<String> REASONS=Set.of("NO_DATA","MISSING_MEASUREMENT","HISTORY_TRUNCATED","NO_CURRENT_TASK","AMBIGUOUS_ORDER");
    public record Rule(Kind kind,int threshold) {
        public Rule {Objects.requireNonNull(kind);int max=switch(kind){case SOURCE_FAILURES,OUTPUT_REJECTIONS->20;case QUEUE_WAIT->3_600_000;case TASK_FAILURE->1;};if(threshold<1||threshold>max)throw new IllegalArgumentException();}
    }
    private static List<Rule> rules(List<Rule> rules){var copy=List.copyOf(rules);if(copy.size()>4||copy.stream().map(Rule::kind).distinct().count()!=copy.size())throw new IllegalArgumentException();return copy.stream().sorted(Comparator.comparing(Rule::kind)).toList();}
    private static void window(int seconds){if(seconds<60||seconds>86400)throw new IllegalArgumentException();}
    public record Command(UUID requestId,String id,int revision,String digest,int expectedVersion,int windowSeconds,List<Rule> rules) {
        public Command {Objects.requireNonNull(requestId);WorkflowDefinition.ref(id,revision);WorkflowDefinition.checkDigest(digest);if(expectedVersion<0||expectedVersion>=1_000_000)throw new IllegalArgumentException();window(windowSeconds);rules=WorkflowQualityAlerts.rules(rules);}
        public WorkflowQuality.Reference reference(){return new WorkflowQuality.Reference(id,revision,digest);}
        public String commandDigest(){var values=new ArrayList<>(List.of("workflow-quality-alerts-v1",id,Integer.toString(revision),digest,Integer.toString(expectedVersion),Integer.toString(windowSeconds)));for(var rule:rules){values.add(rule.kind().name());values.add(Integer.toString(rule.threshold()));}return WorkflowDefinition.hash(values);}
    }
    public record Configuration(WorkflowQuality.Reference reference,int editVersion,int windowSeconds,List<Rule> rules,Instant updatedAt) {
        public Configuration {Objects.requireNonNull(reference);Objects.requireNonNull(updatedAt);if(editVersion<1||editVersion>1_000_000)throw new IllegalArgumentException();window(windowSeconds);rules=WorkflowQualityAlerts.rules(rules);}
    }
    public record Receipt(String schemaVersion,UUID requestId,String commandDigest,Instant acceptedAt,Configuration configuration) {
        public Receipt {if(!"2.0".equals(schemaVersion))throw new IllegalArgumentException();Objects.requireNonNull(requestId);WorkflowDefinition.checkDigest(commandDigest);Objects.requireNonNull(configuration);var ref=configuration.reference();var command=new Command(requestId,ref.id(),ref.revision(),ref.digest(),configuration.editVersion()-1,configuration.windowSeconds(),configuration.rules());if(!configuration.updatedAt().equals(acceptedAt)||!commandDigest.equals(command.commandDigest()))throw new IllegalArgumentException();}
        public void require(Command command){if(!requestId.equals(command.requestId())||!commandDigest.equals(command.commandDigest())||!configuration.reference().equals(command.reference())||configuration.editVersion()!=command.expectedVersion()+1||configuration.windowSeconds()!=command.windowSeconds()||!configuration.rules().equals(command.rules()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);}
    }
    public record Evaluation(Kind kind,State state,Long value,String unit,int sampleCount,int missingCount,List<UUID> evidenceIds,boolean truncated,String reason) {
        public Evaluation {Objects.requireNonNull(kind);Objects.requireNonNull(state);evidenceIds=List.copyOf(evidenceIds);if(!unit.equals(kind==Kind.QUEUE_WAIT?"MILLISECONDS":"COUNT")||sampleCount<0||sampleCount>20||missingCount<0||missingCount>20||sampleCount+missingCount>20||evidenceIds.size()>sampleCount+missingCount||evidenceIds.stream().distinct().count()!=evidenceIds.size()||state==State.UNAVAILABLE!=(value==null)||state==State.UNAVAILABLE!=(reason!=null)||reason!=null&&!REASONS.contains(reason)||value!=null&&(sampleCount==0||value<0||value>(kind==Kind.QUEUE_WAIT?3_600_000:kind==Kind.TASK_FAILURE?1:20))||state==State.NORMAL&&missingCount>0)throw new IllegalArgumentException();}
    }
    public record Status(String schemaVersion,Instant asOf,WorkflowQuality.Reference reference,Configuration configuration,Instant from,Instant till,List<Evaluation> evaluations) {
        public Status {Objects.requireNonNull(asOf);Objects.requireNonNull(reference);evaluations=List.copyOf(evaluations);if(!"2.0".equals(schemaVersion)||configuration==null&&(from!=null||till!=null||!evaluations.isEmpty())||configuration!=null&&(!configuration.reference().equals(reference)||configuration.updatedAt().isAfter(asOf)||!asOf.equals(till)||!asOf.minusSeconds(configuration.windowSeconds()).equals(from)||evaluations.size()!=configuration.rules().size()))throw new IllegalArgumentException();if(configuration!=null)for(int i=0;i<evaluations.size();i++){var e=evaluations.get(i);var rule=configuration.rules().get(i);if(e.kind()!=rule.kind()||e.value()!=null&&(e.state()==State.TRIGGERED)!=(e.value()>=rule.threshold()))throw new IllegalArgumentException();}}
    }
    private static Evaluation unavailable(Rule rule,int samples,int missing,List<UUID> ids,boolean truncated,String reason){return new Evaluation(rule.kind(),State.UNAVAILABLE,null,rule.kind()==Kind.QUEUE_WAIT?"MILLISECONDS":"COUNT",samples,missing,ids,truncated,reason);}
    private static Evaluation measured(Rule rule,long value,int samples,List<UUID> ids,boolean truncated){return new Evaluation(rule.kind(),value>=rule.threshold()?State.TRIGGERED:State.NORMAL,value,rule.kind()==Kind.QUEUE_WAIT?"MILLISECONDS":"COUNT",samples,0,ids,truncated,null);}
    private record Point(UUID id,Instant at,Boolean failed) {}
    private static Evaluation chain(Rule rule,List<Point> points,boolean tail){
        if(points.isEmpty())return unavailable(rule,0,0,List.of(),false,"NO_DATA");
        var ids=new ArrayList<UUID>();int failures=0,known=0,missing=0;boolean reset=false,ambiguous=false;
        for(int i=0;i<points.size();){int end=i+1;while(end<points.size()&&points.get(end).at().equals(points.get(i).at()))end++;
            var group=points.subList(i,end);var types=new HashSet<Boolean>();for(var p:group)types.add(p.failed());
            if(types.size()>1){ambiguous=true;for(var p:group){ids.add(p.id());if(p.failed()==null)missing++;else known++;}break;}
            for(var p:group){ids.add(p.id());if(p.failed()==null){missing++;break;}known++;if(!p.failed()){reset=true;break;}failures++;if(failures>=rule.threshold())break;}
            if(missing>0||reset||failures>=rule.threshold())break;i=end;
        }
        if(ambiguous)return unavailable(rule,known,missing,ids,tail,"AMBIGUOUS_ORDER");
        if(failures>=rule.threshold())return measured(rule,failures,known,ids,tail);
        if(missing>0)return unavailable(rule,known,missing,ids,tail,"MISSING_MEASUREMENT");
        if(tail&&!reset)return unavailable(rule,known,0,ids,true,"HISTORY_TRUNCATED");
        return measured(rule,failures,known,ids,tail);
    }
    private static boolean inWindow(Instant at,Instant from,Instant till){return !at.isBefore(from)&&!at.isAfter(till);}
    public static Status evaluate(Configuration configuration,WorkflowQuality.Report quality,WorkflowDiagnostics.Report diagnostics,boolean currentTask){
        if(!quality.reference().equals(diagnostics.reference())||!quality.asOf().equals(diagnostics.asOf()))throw new IllegalArgumentException();var now=quality.asOf();var ref=quality.reference();
        if(configuration==null)return new Status("2.0",now,ref,null,null,null,List.of());
        if(!configuration.reference().equals(ref)||configuration.updatedAt().isAfter(now))throw new IllegalArgumentException();var from=now.minusSeconds(configuration.windowSeconds());
        var observations=diagnostics.observations().stream().filter(o->inWindow(o.completedAt(),from,now)).sorted(Comparator.comparing(WorkflowDiagnostics.Observation::completedAt).reversed().thenComparing(o->o.id().toString())).toList();
        var batches=quality.batches().stream().filter(b->inWindow(b.observedAt(),from,now)).sorted(Comparator.comparing(WorkflowQuality.Batch::observedAt).reversed().thenComparing(b->b.id().toString())).toList();
        boolean diagnosticTail=diagnostics.truncated()&&observations.size()==20,batchTail=quality.truncated()&&batches.size()==20;var values=new ArrayList<Evaluation>();
        for(var rule:configuration.rules()){
            if(rule.kind()==Kind.TASK_FAILURE){var task=quality.task();values.add(!currentTask||task==null?unavailable(rule,0,0,List.of(),false,"NO_CURRENT_TASK"):measured(rule,task.state().equals("FAILED")?1:0,1,List.of(),false));continue;}
            if(rule.kind()==Kind.QUEUE_WAIT){
                if(observations.isEmpty()){values.add(unavailable(rule,0,0,List.of(),false,"NO_DATA"));continue;}
                var dispatches=new HashMap<UUID,WorkflowDiagnostics.Dispatch>();var ids=new ArrayList<UUID>();long peak=0;int missing=0;
                for(var o:observations){var d=o.dispatch();if(d==null){missing++;continue;}var previous=dispatches.putIfAbsent(d.id(),d);if(previous!=null&&!previous.equals(d))throw new IllegalArgumentException();if(previous==null){if(d.waitMillis()>peak){peak=d.waitMillis();ids.clear();ids.add(o.id());}else if(d.waitMillis()==peak)ids.add(o.id());}}
                if(!dispatches.isEmpty()&&peak>=rule.threshold())values.add(new Evaluation(rule.kind(),State.TRIGGERED,peak,"MILLISECONDS",dispatches.size(),missing,ids,diagnosticTail,null));
                else if(missing>0||dispatches.isEmpty())values.add(unavailable(rule,dispatches.size(),missing,ids,diagnosticTail,"MISSING_MEASUREMENT"));
                else if(diagnosticTail)values.add(unavailable(rule,dispatches.size(),0,ids,true,"HISTORY_TRUNCATED"));
                else values.add(measured(rule,peak,dispatches.size(),ids,false));continue;
            }
            var points=new ArrayList<Point>();boolean tail=rule.kind()==Kind.SOURCE_FAILURES?diagnosticTail:batchTail;
            if(rule.kind()==Kind.SOURCE_FAILURES){for(var o:observations)points.add(new Point(o.id(),o.completedAt(),o.sourceRead()==null?null:o.sourceRead().failed()>0));}
            else {for(var b:batches){var n=b.counts().outputRejected();points.add(new Point(b.id(),b.observedAt(),n==null||Set.of("UNKNOWN","IN_FLIGHT","READY").contains(b.state())?null:n>0));}}
            values.add(chain(rule,points,tail));
        }
        return new Status("2.0",now,ref,configuration,from,now,values);
    }
}
