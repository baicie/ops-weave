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
  List<SourceSetup> setups(String owner);
  void saveSetup(String owner,SourceSetup setup);
 }
 @FunctionalInterface interface Work<T> { T apply(Session session); }
 <T> T transaction(TenantId tenant,Work<T> work);
}
