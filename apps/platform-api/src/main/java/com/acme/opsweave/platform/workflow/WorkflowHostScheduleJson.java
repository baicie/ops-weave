package com.acme.opsweave.platform.workflow;
import com.acme.opsweave.integration.domain.WorkflowHostSchedule;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowHostScheduleJson {
    private WorkflowHostScheduleJson(){}
    private static final Set<String> KEYS=Set.of("workflowId","revision","digest","settings","intervalSeconds","generation","state","taskGeneration","activeScanId","accountedScanId","completedScans","confirmedRecords","sessionBatches","nextRunAt","lastSuccessAt","updatedAt","error");
    private static void exact(JsonNode n,Set<String> keys){fields(n,keys);if(n.size()!=keys.size())throw new IllegalArgumentException();}
    private static void node(JsonNode n){exact(n,KEYS);for(var k:List.of("revision","intervalSeconds","generation","taskGeneration","completedScans","confirmedRecords","sessionBatches"))integer(n,k,null);for(var k:List.of("workflowId","digest","state","updatedAt"))text(n,k);for(var k:List.of("activeScanId","accountedScanId","nextRunAt","lastSuccessAt","error"))if(!n.get(k).isNull())text(n,k);WorkflowRuntimeJson.settings(n.get("settings"));}
    public static WorkflowHostSchedule.Schedule schedule(String body){try{var n=CatalogJson.JSON.readTree(body);node(n);return CatalogJson.JSON.treeToValue(n,WorkflowHostSchedule.Schedule.class);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid host schedule storage");}}
    public static WorkflowHostSchedule.Receipt receipt(String body){try{var n=CatalogJson.JSON.readTree(body);exact(n,Set.of("requestId","operation","commandDigest","acceptedAt","schedule"));for(var k:List.of("requestId","operation","commandDigest","acceptedAt"))text(n,k);node(n.get("schedule"));return CatalogJson.JSON.treeToValue(n,WorkflowHostSchedule.Receipt.class);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid host schedule control storage");}}
}
