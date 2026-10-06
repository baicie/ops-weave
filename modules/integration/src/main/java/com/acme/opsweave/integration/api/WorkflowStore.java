package com.acme.opsweave.integration.api;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

public interface WorkflowStore {
 record Position(int x,int y) { public Position { if(x<0||y<0||x>4000||y>4000) throw new IllegalArgumentException(); } }
 record Receipt(UUID id,String digest,String inputDigest,String origin,int accepted,int rejected,int filtered,Instant createdAt) {
  public Receipt { Objects.requireNonNull(id); WorkflowDefinition.checkDigest(digest); WorkflowDefinition.checkDigest(inputDigest); if(!Set.of("MANUAL_SAMPLE","fixture","zabbix-jsonrpc").contains(origin)||accepted<0||rejected<0||filtered<0||accepted+rejected+filtered<1||accepted+rejected+filtered>5) throw new IllegalArgumentException(); Objects.requireNonNull(createdAt); }
  public boolean publishable(Instant now) { return accepted>0 && rejected==0 && !createdAt.isAfter(now) && createdAt.plusSeconds(900).isAfter(now); }
 }
 record Entry(WorkflowDefinition definition,String digest,String state,int editVersion,Map<String,Position> layout,Instant updatedAt,Receipt preview) {
  public Entry { if(!definition.digest().equals(digest)||!Set.of("DRAFT","PUBLISHED").contains(state)||editVersion<0||editVersion>1000000||state.equals("DRAFT")&&editVersion==0||state.equals("PUBLISHED")&&editVersion!=0) throw new IllegalArgumentException(); layout=Map.copyOf(layout); if(!layout.keySet().equals(definition.nodes().stream().map(WorkflowDefinition.Node::id).collect(java.util.stream.Collectors.toSet())))throw new IllegalArgumentException(); Objects.requireNonNull(updatedAt); if(preview!=null&&!preview.digest().equals(digest)) throw new IllegalArgumentException(); }
 }
 record Run(String workflowId,int revision,String mode,Receipt receipt,WorkflowTrace trace) { public Run { WorkflowDefinition.ref(workflowId,revision); if(!Set.of("PREVIEW","RUN").contains(mode)) throw new IllegalArgumentException(); Objects.requireNonNull(receipt);if(trace!=null)trace.requireReceipt(receipt); } public Run(String workflowId,int revision,String mode,Receipt receipt){this(workflowId,revision,mode,receipt,null);} }
 interface Session {
  Optional<com.acme.opsweave.integration.domain.WorkflowLogReplay.Plan> logReplayPlan(String owner,UUID id);
  List<com.acme.opsweave.integration.domain.WorkflowLogReplay.Plan> logReplayPlans(String owner,com.acme.opsweave.integration.domain.WorkflowQuality.Reference ref);
  int logReplayPlanCount(String owner);
  void addLogReplayPlan(String owner,com.acme.opsweave.integration.domain.WorkflowLogReplay.Plan plan);
  void finishLogReplayPlan(String owner,com.acme.opsweave.integration.domain.WorkflowLogReplay.Plan plan);
  Optional<com.acme.opsweave.integration.domain.WorkflowLogReplay.Receipt> logReplayReceipt(String owner,UUID id);
  Optional<com.acme.opsweave.integration.domain.WorkflowLogReplay.Receipt> logReplayReceiptForPlan(String owner,UUID id);
  void addLogReplayReceipt(String owner,com.acme.opsweave.integration.domain.WorkflowLogReplay.Receipt receipt);
  void finishLogReplayReceipt(String owner,com.acme.opsweave.integration.domain.WorkflowLogReplay.Receipt receipt);

  Optional<com.acme.opsweave.integration.domain.WorkflowMetricReplay.Plan> metricReplayPlan(String owner,UUID id);
  List<com.acme.opsweave.integration.domain.WorkflowMetricReplay.Plan> metricReplayPlans(String owner,com.acme.opsweave.integration.domain.WorkflowQuality.Reference ref);
  int metricReplayPlanCount(String owner);
  void addMetricReplayPlan(String owner,com.acme.opsweave.integration.domain.WorkflowMetricReplay.Plan plan);
  void finishMetricReplayPlan(String owner,com.acme.opsweave.integration.domain.WorkflowMetricReplay.Plan plan);
  Optional<com.acme.opsweave.integration.domain.WorkflowMetricReplay.Receipt> metricReplayReceipt(String owner,UUID id);
  Optional<com.acme.opsweave.integration.domain.WorkflowMetricReplay.Receipt> metricReplayReceiptForPlan(String owner,UUID id);
  void addMetricReplayReceipt(String owner,com.acme.opsweave.integration.domain.WorkflowMetricReplay.Receipt receipt);
  void finishMetricReplayReceipt(String owner,com.acme.opsweave.integration.domain.WorkflowMetricReplay.Receipt receipt);
  long diagnosticHistoryWatermark(String owner,WorkflowQuality.Reference reference);
  int diagnosticHistoryCount(String owner,WorkflowQuality.Reference reference,Instant snapshotAt,long watermark);
  Optional<WorkflowHistory.Row> diagnosticHistoryRow(String owner,UUID id);
  List<WorkflowHistory.Row> diagnosticHistoryRows(String owner,WorkflowQuality.Reference reference,Instant snapshotAt,long watermark,WorkflowHistory.Row anchor);
  Optional<WorkflowQualityAlerts.Configuration> alertConfiguration(String owner,String id,int revision);
  void saveAlertConfiguration(String owner,WorkflowQualityAlerts.Configuration configuration,int expectedVersion);
  Optional<WorkflowQualityAlerts.Receipt> alertCommand(String owner,UUID requestId);
  int alertCommandCount(String owner);
  void addAlertCommand(String owner,WorkflowQualityAlerts.Receipt receipt);
  Optional<WorkflowSampleRecovery.Receipt> sampleRecovery(String owner,UUID id);
  Optional<WorkflowSampleRecovery.Receipt> sampleRecoveryForBatch(String owner,WorkflowSampleRecovery.Kind kind,UUID batchId);
  int sampleRecoveryCount(String owner);
  void addSampleRecovery(String owner,WorkflowSampleRecovery.Receipt receipt);
  Optional<WorkflowTaskArchive> taskArchive(String owner,String id,int revision,String digest);
  int taskArchiveCount(String owner);
  void addTaskArchive(String owner,WorkflowTaskArchive archive);
  Optional<WorkflowRecovery.Receipt> recovery(String owner,UUID requestId);
  Optional<WorkflowRecovery.Receipt> recoveryForVersion(String owner,String id,int revision,String digest);
  int recoveryCount(String owner);
  void addRecovery(String owner,WorkflowRecovery.Receipt receipt);
  Optional<WorkflowDiagnostics.Stored> diagnostic(String owner,UUID id);
  List<WorkflowDiagnostics.Stored> diagnostics(String owner,String id,int revision,String digest);
  int diagnosticCount(String owner);
  void addDiagnostic(String owner,WorkflowDiagnostics.Stored diagnostic);
  Optional<SourceCredential> credential(String owner,UUID id);
  boolean credentialExists(UUID id);
  List<SourceCredential> credentials(String owner);
  void saveCredential(String owner,SourceCredential credential);
  List<SourceCredential.Version> credentialVersions(String owner,UUID id);
  void addCredentialVersion(String owner,SourceCredential.Version version);
  List<SourceCredential.Revocation> credentialRevocations(String owner,UUID id);
  void addCredentialRevocation(String owner,SourceCredential.Revocation revocation);
  Optional<SourceCredential.Receipt> credentialReceipt(String owner,UUID id);
  int credentialReceiptCount(String owner);
  void addCredentialReceipt(String owner,SourceCredential.Receipt receipt);
  Optional<Entry> draft(String owner,String id,int revision);
  Optional<Entry> published(String id,int revision);
  List<Entry> drafts(String owner);
  List<Entry> published();
  void saveDraft(String owner,Entry entry);
  void publish(Entry entry,String owner);
  void addRun(String owner,Run run);
  List<Run> runs(String owner);
  default Optional<Run> run(String owner,UUID id){return runs(owner).stream().filter(r->r.receipt().id().equals(id)).findFirst();}
  Optional<SourceSetup> setup(String owner,UUID id);
  boolean sourceSetupExists(UUID id);
  List<SourceSetup> setups(String owner);
  void saveSetup(String owner,SourceSetup setup);
  Optional<SourceInstance> sourceInstance(String owner,UUID id);
  void saveSourceInstance(String owner,SourceInstance instance);
  List<SourceInstance.Configuration> sourceConfigurations(String owner,UUID id);
  void addSourceConfiguration(String owner,SourceInstance.Configuration configuration);
  List<SourceConnectionConfiguration> sourceConnections(String owner,UUID id);
  void addSourceConnection(String owner,SourceConnectionConfiguration configuration);
  Optional<SourceInstance.CommandReceipt> sourceCommand(String owner,UUID requestId);
  int sourceCommandCount(String owner);
  void addSourceCommand(String owner,SourceInstance.CommandReceipt receipt);
  Optional<com.acme.opsweave.integration.domain.SourceInspection> sourceInspection(String owner,UUID requestId);
  List<com.acme.opsweave.integration.domain.SourceInspection> sourceInspections(String owner,UUID sourceId);
  List<com.acme.opsweave.integration.domain.SourceInspection> sourceInspections(String owner,UUID sourceId,String kind);
  int sourceInspectionCount(String owner);
  void addSourceInspection(String owner,com.acme.opsweave.integration.domain.SourceInspection inspection);
  void finishSourceInspection(String owner,com.acme.opsweave.integration.domain.SourceInspection inspection);
  Optional<WorkflowLogStream.Task> logStreamTask(String owner,String id);
  List<WorkflowLogStream.Task> logStreamTasks(String owner);
  void saveLogStreamTask(String owner,WorkflowLogStream.Task task);
  Optional<WorkflowLogStream.Batch> logStreamBatch(String owner,UUID id);
  List<WorkflowLogStream.Batch> logStreamBatches(String owner,String id);
  /** Fixed version, newest first; at most 21 rows so the public 20-row list reports truncation. */
  List<WorkflowLogStream.Batch> qualityLogBatches(String owner,String id,int revision,String digest);
  Optional<WorkflowLogStream.Batch> latestConfirmedLogWindow(String owner,String id,int revision,String digest,java.time.Instant till);
  int logStreamBatchCount(String owner);
  void addLogStreamBatch(String owner,WorkflowLogStream.Batch batch);
  void finishLogStreamBatch(String owner,WorkflowLogStream.Batch batch);
  boolean logStreamRejectionKnown(String owner,UUID id);
  Optional<WorkflowLogStream.Receipt> logStreamControl(String owner,UUID id);
  int logStreamControlCount(String owner);
  void addLogStreamControl(String owner,WorkflowLogStream.Receipt receipt);
  Optional<WorkflowMetricStream.Task> metricStreamTask(String owner,String id);
  List<WorkflowMetricStream.Task> metricStreamTasks(String owner);
  void saveMetricStreamTask(String owner,WorkflowMetricStream.Task task);
  Optional<WorkflowMetricStream.Batch> metricStreamBatch(String owner,UUID id);
  List<WorkflowMetricStream.Batch> metricStreamBatches(String owner,String id);
  List<WorkflowMetricStream.Batch> qualityMetricBatches(String owner,String id,int revision,String digest);
  Optional<WorkflowMetricStream.Batch> latestConfirmedMetricWindow(String owner,String id,int revision,String digest,java.time.Instant till);
  int metricStreamBatchCount(String owner);
  void addMetricStreamBatch(String owner,WorkflowMetricStream.Batch batch);
  void finishMetricStreamBatch(String owner,WorkflowMetricStream.Batch batch);
  Optional<WorkflowMetricStream.Receipt> metricStreamControl(String owner,UUID id);
  int metricStreamControlCount(String owner);
  void addMetricStreamControl(String owner,WorkflowMetricStream.Receipt receipt);
  Optional<WorkflowRuntime.Task> task(String owner,String id);
  Optional<WorkflowHostSchedule.Schedule> hostSchedule(String owner,String id);
  List<WorkflowHostSchedule.Schedule> hostSchedules(String owner);
  void saveHostSchedule(String owner,WorkflowHostSchedule.Schedule schedule);
  Optional<WorkflowHostSchedule.Receipt> hostScheduleControl(String owner,UUID id);
  int hostScheduleControlCount(String owner);
  void addHostScheduleControl(String owner,WorkflowHostSchedule.Receipt receipt);
  Optional<WorkflowHostScan.Checkpoint> hostCheckpoint(String owner,String id);
  void saveHostCheckpoint(String owner,WorkflowHostScan.Checkpoint checkpoint);
  Optional<WorkflowHostScan.Batch> hostBatch(String owner,UUID id);
  List<WorkflowHostScan.Batch> hostBatches(String owner,String workflowId);
  List<WorkflowHostScan.Batch> qualityHostBatches(String owner,String id,int revision,String digest);
  int hostBatchCount(String owner);
  void addHostBatch(String owner,WorkflowHostScan.Batch batch);
  void finishHostBatch(String owner,WorkflowHostScan.Batch batch);
  Optional<WorkflowLogOutput.Receipt> logOutput(String owner,UUID id);
  List<WorkflowLogOutput.Receipt> logOutputs(String owner,String workflowId);
  int logOutputCount(String owner);
  void addLogOutput(String owner,WorkflowLogOutput.Receipt receipt);
  void finishLogOutput(String owner,WorkflowLogOutput.Receipt receipt);
  Optional<WorkflowMetricOutput.Receipt> metricOutput(String owner,UUID id);
  List<WorkflowMetricOutput.Receipt> metricOutputs(String owner,String workflowId);
  int metricOutputCount(String owner);
  void addMetricOutput(String owner,WorkflowMetricOutput.Receipt receipt);
  void finishMetricOutput(String owner,WorkflowMetricOutput.Receipt receipt);
  Optional<WorkflowRuntimeControl.Receipt> runtimeControl(String owner,UUID requestId);
  int runtimeControlCount(String owner);
  void addRuntimeControl(String owner,WorkflowRuntimeControl.Receipt receipt);
  List<WorkflowRuntime.Task> tasks(String owner);
  void saveTask(String owner,WorkflowRuntime.Task task);
  Optional<WorkflowRuntime.Execution> execution(String owner,UUID id);
  List<WorkflowRuntime.Execution> executions(String owner);
  void addExecution(String owner,WorkflowRuntime.Execution execution);
 }
 @FunctionalInterface interface Work<T> { T apply(Session session); }
 <T> T transaction(TenantId tenant,Work<T> work);
}
