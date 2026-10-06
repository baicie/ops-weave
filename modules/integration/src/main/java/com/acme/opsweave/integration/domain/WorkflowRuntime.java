package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Fixed-version ingestion control and metadata only; never persists sample bodies or credentials. */
public final class WorkflowRuntime {
 private WorkflowRuntime() {}
 public record Settings(String identityField,String nameField) {
  public Settings { WorkflowDefinition.field(identityField);WorkflowDefinition.field(nameField); }
 }
 public record Task(String workflowId,int revision,String digest,Settings settings,long generation,String state,Instant cursor,UUID cursorId,Instant updatedAt,String error,WorkflowTaskAuthority authority) {
  public Task(String workflowId,int revision,String digest,Settings settings,long generation,String state,Instant cursor,UUID cursorId,Instant updatedAt,String error){this(workflowId,revision,digest,settings,generation,state,cursor,cursorId,updatedAt,error,null);}
  public Task { WorkflowDefinition.ref(workflowId,revision);WorkflowDefinition.checkDigest(digest);Objects.requireNonNull(settings);Objects.requireNonNull(cursor);Objects.requireNonNull(cursorId);Objects.requireNonNull(updatedAt);if(generation<1||generation>1000001||generation==1000001&&!Set.of("STOPPED","ABANDONED").contains(state)||!Set.of("RUNNING","STOPPED","FAILED","ABANDONED").contains(state))throw new IllegalArgumentException();checkError(error);if(state.equals("ABANDONED")&&(!"OUTPUT_UNAVAILABLE".equals(error)||authority!=null))throw new IllegalArgumentException();if(Set.of("FAILED","ABANDONED").contains(state)!=(error!=null))throw new IllegalArgumentException(); }
 }
 public record Execution(UUID id,String workflowId,int revision,String digest,Settings settings,String origin,UUID syncRunId,Instant createdAt,String state,int accepted,int rejected,int filtered,List<String> entityIds,String error,UUID authorizationId) {
  public Execution(UUID id,String workflowId,int revision,String digest,Settings settings,String origin,UUID syncRunId,Instant createdAt,String state,int accepted,int rejected,int filtered,List<String> entityIds,String error){this(id,workflowId,revision,digest,settings,origin,syncRunId,createdAt,state,accepted,rejected,filtered,entityIds,error,null);}
  public Execution { Objects.requireNonNull(id);WorkflowDefinition.ref(workflowId,revision);WorkflowDefinition.checkDigest(digest);Objects.requireNonNull(settings);Objects.requireNonNull(createdAt);entityIds=List.copyOf(entityIds);if(!Set.of("MANUAL_SAMPLE","fixture","zabbix-jsonrpc").contains(origin)||!Set.of("SUCCEEDED","FAILED").contains(state)||accepted<0||rejected<0||filtered<0||accepted+rejected+filtered>5||entityIds.size()>accepted||state.equals("SUCCEEDED")&&entityIds.size()!=accepted||Set.of("FAILED","ABANDONED").contains(state)!=(error!=null))throw new IllegalArgumentException();for(var entity:entityIds)UUID.fromString(entity);checkError(error); }
  public int written(){return entityIds.size();}
 }
 private static void checkError(String error){if(error!=null&&!Set.of("INVALID_SAMPLE","SOURCE_UNAVAILABLE","FORBIDDEN","MODEL_CHANGED","OPERATOR_CHANGED","OUTPUT_UNAVAILABLE","BACKLOG_LIMIT","RUNTIME_UNAVAILABLE","AUTHORIZATION_EXPIRED","AUTHORIZATION_REVOKED","EXECUTION_LIMIT").contains(error))throw new IllegalArgumentException();}
}
