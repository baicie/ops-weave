package com.acme.opsweave.integration.domain;
import com.acme.opsweave.catalog.domain.ModelPreview;
import com.acme.opsweave.integration.api.WorkflowStore.Receipt;
import java.time.Instant;
import java.util.*;

/** Persisted execution metadata only: never input, output, node configuration or exception text. */
public record WorkflowTrace(WorkflowDefinition.Source source, WorkflowDefinition.Target target, UUID syncRunId,
 Instant startedAt, long durationMillis, long retainedCount, long missingRaw, boolean truncated, String sourceStatus,
 boolean dryRun, boolean writesPerformed, List<Row> rows) {
 public static final Set<String> ISSUE_CODES=Set.of("UNKNOWN_FIELD","MISSING_REQUIRED","NULL_REQUIRED","TOO_LONG","EMPTY_REQUIRED","ENUM_MISMATCH","OUT_OF_RANGE","TYPE_MISMATCH","NUMERIC_LIMIT","TRANSFORM_FAILED","MERGE_CONFLICT");
 public record Step(String nodeId,WorkflowDefinition.Type type,String status,List<ModelPreview.Issue> issues) {
  public Step {
   if(nodeId==null||!nodeId.matches("[a-z][a-z0-9_-]{0,31}")||type==null||!Set.of("OK","ERROR","FILTERED","SKIPPED").contains(status))throw new IllegalArgumentException();
   issues=List.copyOf(issues);if(issues.size()>64||status.equals("ERROR")!=!issues.isEmpty())throw new IllegalArgumentException();
   for(var issue:issues){WorkflowDefinition.field(issue.field());if(!ISSUE_CODES.contains(issue.code()))throw new IllegalArgumentException();}
   if(status.equals("FILTERED")&&type!=WorkflowDefinition.Type.FILTER)throw new IllegalArgumentException();
  }
 }
 public record Row(int index,String status,List<Step> steps) {
  public Row {
   steps=List.copyOf(steps);if(index<0||index>4||!Set.of("ACCEPTED","REJECTED","FILTERED").contains(status)||steps.size()<4||steps.size()>16)throw new IllegalArgumentException();
   var ids=new HashSet<String>();boolean error=false,filtered=false;
   for(int i=0;i<steps.size();i++){
    var step=steps.get(i);if(!ids.add(step.nodeId()))throw new IllegalArgumentException();
    var type=step.type();if(i==0?type!=WorkflowDefinition.Type.SOURCE:i==1?type!=WorkflowDefinition.Type.MAP:i==steps.size()-2?type!=WorkflowDefinition.Type.VALIDATE:i==steps.size()-1?type!=WorkflowDefinition.Type.OUTPUT:Set.of(WorkflowDefinition.Type.SOURCE,WorkflowDefinition.Type.MAP,WorkflowDefinition.Type.VALIDATE,WorkflowDefinition.Type.OUTPUT).contains(type))throw new IllegalArgumentException();
    error|=step.status().equals("ERROR");filtered|=step.status().equals("FILTERED");
    if(i==0&&!step.status().equals("OK"))throw new IllegalArgumentException();
   }
   String outcome=error?"REJECTED":steps.getLast().status().equals("OK")?"ACCEPTED":filtered?"FILTERED":"INVALID";
   if(!status.equals(outcome))throw new IllegalArgumentException();
  }
 }
 public WorkflowTrace {
  Objects.requireNonNull(source);Objects.requireNonNull(target);Objects.requireNonNull(startedAt);rows=List.copyOf(rows);
  if(durationMillis<0||durationMillis>9007199254740991L||rows.isEmpty()||rows.size()>5||retainedCount<rows.size()||missingRaw<0||retainedCount>9007199254740991L||missingRaw>9007199254740991L||!dryRun||writesPerformed)throw new IllegalArgumentException();
  if(source.kind().equals("MANUAL_SAMPLE")?(syncRunId!=null||!sourceStatus.equals("MANUAL_SAMPLE")||missingRaw!=0||truncated||retainedCount!=rows.size()):((source.configuration()==null?syncRunId==null:syncRunId!=null)||!Set.of("SUCCEEDED","FAILED").contains(sourceStatus)))throw new IllegalArgumentException();
  for(int i=0;i<rows.size();i++){
   var row=rows.get(i);if(row.index()!=i||row.steps().size()!=rows.getFirst().steps().size())throw new IllegalArgumentException();
   for(int j=0;j<row.steps().size();j++)if(!row.steps().get(j).nodeId().equals(rows.getFirst().steps().get(j).nodeId())||row.steps().get(j).type()!=rows.getFirst().steps().get(j).type())throw new IllegalArgumentException();
  }
 }
 public void requireReceipt(Receipt receipt){
  if(startedAt.isAfter(receipt.createdAt())||source.kind().equals("MANUAL_SAMPLE")!=receipt.origin().equals("MANUAL_SAMPLE")||rows.stream().filter(r->r.status().equals("ACCEPTED")).count()!=receipt.accepted()||rows.stream().filter(r->r.status().equals("REJECTED")).count()!=receipt.rejected()||rows.stream().filter(r->r.status().equals("FILTERED")).count()!=receipt.filtered())throw new IllegalArgumentException();
 }
 public static List<Row> metadata(WorkflowEvaluation evaluation){return evaluation.rows().stream().map(r->new Row(r.index(),r.status(),r.steps().stream().map(s->new Step(s.nodeId(),WorkflowDefinition.Type.valueOf(s.type()),s.status(),s.issues())).toList())).toList();}
}
