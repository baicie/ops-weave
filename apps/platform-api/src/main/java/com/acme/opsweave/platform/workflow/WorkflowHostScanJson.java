package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.WorkflowHostScan;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowHostScanJson {
    private WorkflowHostScanJson() {}
    public static WorkflowHostScan.Checkpoint checkpoint(String body) {
        var n=CatalogJson.JSON.readTree(body);
        fields(n,Set.of("workflowId","revision","digest","generation","scanId","nextCursor","pendingBatchId","confirmedBatches","confirmedRecords","complete","updatedAt"));
        if(n.size()!=11||!n.get("complete").isBoolean())throw new IllegalArgumentException();
        for(var key:List.of("revision","generation","confirmedBatches","confirmedRecords"))integer(n,key,null);
        for(var key:List.of("nextCursor","pendingBatchId"))if(!n.get(key).isNull())text(n,key);
        try{return CatalogJson.JSON.treeToValue(n,WorkflowHostScan.Checkpoint.class);}
        catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid host checkpoint storage");}
    }
    public static WorkflowHostScan.Batch batch(String body) {
        var n=CatalogJson.JSON.readTree(body);
        fields(n,Set.of("id","scanId","workflowId","revision","digest","settings","sequence","beforeCursor","nextCursor","complete","observedAt","records","state","entityIds","error","updatedAt"));
        if(n.size()!=16||!n.get("complete").isBoolean()||!n.get("entityIds").isArray()||n.get("entityIds").size()>5)throw new IllegalArgumentException();
        for(var key:List.of("revision","sequence"))integer(n,key,null);
        for(var key:List.of("beforeCursor","nextCursor","error"))if(!n.get(key).isNull())text(n,key);
        for(var id:n.get("entityIds"))if(!id.isString())throw new IllegalArgumentException();
        WorkflowRuntimeJson.settings(n.get("settings"));
        if(!n.get("records").isArray()||n.get("records").size()>5)throw new IllegalArgumentException();
        for(var row:n.get("records")){fields(row,Set.of("name","ip","lifecycle","entity_id"));if(row.size()!=4)throw new IllegalArgumentException();for(var key:List.of("name","ip","lifecycle","entity_id"))if(!row.get(key).isString())throw new IllegalArgumentException();}
        try{return CatalogJson.JSON.treeToValue(n,WorkflowHostScan.Batch.class);}
        catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid host batch storage");}
    }
    public static Object wire(WorkflowHostScan.Checkpoint c) {
        var n=(tools.jackson.databind.node.ObjectNode)CatalogJson.JSON.valueToTree(c);n.remove("nextCursor");return n;
    }
    public static Object wire(WorkflowHostScan.Batch b) {
        var n=(tools.jackson.databind.node.ObjectNode)CatalogJson.JSON.valueToTree(b);n.remove("records");n.remove("beforeCursor");n.remove("nextCursor");n.put("recordCount",b.records().size());return n;
    }
}
