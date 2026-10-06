package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.WorkflowLogStream;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowLogStreamJson {
    private WorkflowLogStreamJson(){}
    private static final Set<String> TASK=Set.of("workflowId","revision","digest","generation","state","cursor","pendingBatchId","confirmedWindows","confirmedRecords","sessionBatches","updatedAt","error","authority");
    private static final Set<String> BATCH=Set.of("id","workflowId","revision","digest","from","till","createdAt","updatedAt","state","inputCount","filtered","deduplicated","indices","positions","inputDigest","batchDigest","error","reconcilesBatchId");
    private static void exact(JsonNode n,Set<String> keys){fields(n,keys);if(n.size()!=keys.size())throw new IllegalArgumentException();}
    private static void taskNode(JsonNode n){exact(n,TASK);for(var k:List.of("revision","generation","confirmedWindows","confirmedRecords","sessionBatches"))integer(n,k,null);for(var k:List.of("workflowId","digest","state","cursor","updatedAt"))text(n,k);for(var k:List.of("pendingBatchId","error"))if(!n.get(k).isNull())text(n,k);if(!n.get("authority").isNull())WorkflowRuntimeJson.authority(n.get("authority"));}
    public static WorkflowLogStream.Task task(String body){try{var n=CatalogJson.JSON.readTree(body);taskNode(n);return CatalogJson.JSON.treeToValue(n,WorkflowLogStream.Task.class);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid log stream storage");}}
    public static WorkflowLogStream.Batch batch(String body){try{
        var n=CatalogJson.JSON.readTree(body);exact(n,BATCH);
        for(var k:List.of("revision","inputCount","filtered","deduplicated"))integer(n,k,null);
        for(var k:List.of("id","workflowId","digest","from","till","createdAt","updatedAt","state","inputDigest","batchDigest"))text(n,k);
        for(var k:List.of("error","reconcilesBatchId"))if(!n.get(k).isNull())text(n,k);
        for(var k:List.of("indices","positions"))if(!n.get(k).isArray()||n.get(k).size()>com.acme.opsweave.integration.domain.WorkflowLogWindow.MAX_RECORDS)throw new IllegalArgumentException();
        for(var value:n.get("indices"))if(!value.isIntegralNumber()||!value.canConvertToInt())throw new IllegalArgumentException();
        for(var value:n.get("positions"))if(!value.isString())throw new IllegalArgumentException();
        return CatalogJson.JSON.treeToValue(n,WorkflowLogStream.Batch.class);
    }catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid log stream storage");}}
    public static WorkflowLogStream.Receipt receipt(String body){try{var n=CatalogJson.JSON.readTree(body);exact(n,Set.of("requestId","operation","commandDigest","acceptedAt","task"));for(var k:List.of("requestId","operation","commandDigest","acceptedAt"))text(n,k);taskNode(n.get("task"));return CatalogJson.JSON.treeToValue(n,WorkflowLogStream.Receipt.class);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid log stream storage");}}
    public static Object wire(WorkflowLogStream.Task task){if(task==null)return null;var n=(ObjectNode)CatalogJson.JSON.valueToTree(task);n.remove("authority");if(task.authority()!=null){var a=task.authority();n.set("authorization",CatalogJson.JSON.valueToTree(Map.of("id",a.id(),"issuedAt",a.issuedAt(),"expiresAt",a.expiresAt(),"maxBatches",a.maxBatches(),"consumedBatches",a.consumedBatches())));}return n;}
    public static Object wire(WorkflowLogStream.Receipt receipt){var n=(ObjectNode)CatalogJson.JSON.valueToTree(receipt);n.set("task",CatalogJson.JSON.valueToTree(wire(receipt.task())));return n;}
}
