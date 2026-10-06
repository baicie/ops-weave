package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.WorkflowMetricStream;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowMetricStreamJson {
    private WorkflowMetricStreamJson(){}
    private static final Set<String> TASK=Set.of("workflowId","revision","digest","generation","state","cursor","pendingBatchId","confirmedWindows","confirmedPoints","sessionBatches","updatedAt","error","authority");
    private static final Set<String> BATCH=Set.of("id","workflowId","revision","digest","from","till","createdAt","updatedAt","state","inputCount","filtered","collapsed","labels","batchDigest","timestamps","error");
    private static void exact(JsonNode n,Set<String> keys){fields(n,keys);if(n.size()!=keys.size())throw new IllegalArgumentException();}
    private static void taskNode(JsonNode n){exact(n,TASK);for(var k:List.of("revision","generation","confirmedWindows","confirmedPoints","sessionBatches"))integer(n,k,null);for(var k:List.of("workflowId","digest","state","cursor","updatedAt"))text(n,k);for(var k:List.of("pendingBatchId","error"))if(!n.get(k).isNull())text(n,k);if(!n.get("authority").isNull())WorkflowRuntimeJson.authority(n.get("authority"));}
    private static WorkflowMetricStream.Batch decodeBatch(JsonNode n){if(!n.has("latePoints")){((ObjectNode)n).putNull("reconcilesBatchId");((ObjectNode)n).put("latePoints",0);}return CatalogJson.JSON.treeToValue(n,WorkflowMetricStream.Batch.class);}
    public static WorkflowMetricStream.Task task(String body){try{var n=CatalogJson.JSON.readTree(body);taskNode(n);return CatalogJson.JSON.treeToValue(n,WorkflowMetricStream.Task.class);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid metric stream storage");}}
    public static WorkflowMetricStream.Batch batch(String body){try{var n=CatalogJson.JSON.readTree(body);var allowed=new HashSet<>(BATCH);allowed.addAll(Set.of("reconcilesBatchId","latePoints"));fields(n,allowed);if(BATCH.stream().anyMatch(k->!n.has(k))||n.has("reconcilesBatchId")!=n.has("latePoints"))throw new IllegalArgumentException();if(n.has("latePoints")){integer(n,"latePoints",null);if(!n.get("reconcilesBatchId").isNull())text(n,"reconcilesBatchId");}for(var k:List.of("revision","inputCount","filtered","collapsed"))integer(n,k,null);for(var k:List.of("id","workflowId","digest","from","till","createdAt","updatedAt","state","batchDigest"))text(n,k);if(!n.get("error").isNull())text(n,"error");if(!n.get("labels").isObject()||!n.get("timestamps").isArray()||n.get("timestamps").size()>WorkflowMetricStream.MAX_POINTS)throw new IllegalArgumentException();for(var label:n.get("labels").properties())if(!label.getValue().isString())throw new IllegalArgumentException();for(var stamp:n.get("timestamps"))if(!stamp.isIntegralNumber()||!stamp.canConvertToLong())throw new IllegalArgumentException();return decodeBatch(n);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid metric stream storage");}}
    public static WorkflowMetricStream.Receipt receipt(String body){try{var n=CatalogJson.JSON.readTree(body);exact(n,Set.of("requestId","operation","commandDigest","acceptedAt","task"));for(var k:List.of("requestId","operation","commandDigest","acceptedAt"))text(n,k);taskNode(n.get("task"));return CatalogJson.JSON.treeToValue(n,WorkflowMetricStream.Receipt.class);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid metric stream storage");}}
    public static Object wire(WorkflowMetricStream.Task task){if(task==null)return null;var n=(ObjectNode)CatalogJson.JSON.valueToTree(task);n.remove("authority");if(task.authority()!=null){var a=task.authority();n.set("authorization",CatalogJson.JSON.valueToTree(Map.of("id",a.id(),"issuedAt",a.issuedAt(),"expiresAt",a.expiresAt(),"maxBatches",a.maxBatches(),"consumedBatches",a.consumedBatches())));}return n;}
    public static Object wire(WorkflowMetricStream.Receipt receipt){var n=(ObjectNode)CatalogJson.JSON.valueToTree(receipt);n.set("task",CatalogJson.JSON.valueToTree(wire(receipt.task())));return n;}
}
