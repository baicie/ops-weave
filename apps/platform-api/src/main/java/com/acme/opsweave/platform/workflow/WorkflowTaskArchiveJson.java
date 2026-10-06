package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.WorkflowTaskArchive;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.util.Set;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

/** Private persistence schema; authority, cursor, source records and identity fields are absent. */
public final class WorkflowTaskArchiveJson {
    private WorkflowTaskArchiveJson() {}
    public static WorkflowTaskArchive decode(String json) {
        var n=CatalogJson.JSON.readTree(json);
        fields(n,Set.of("schemaVersion","reference","kind","task","schedule","replacedBy","nextGeneration","replacedAt"));
        if(n.size()!=8)throw new IllegalArgumentException();
        for(var key:Set.of("reference","replacedBy")) {
            var ref=n.get(key); fields(ref,Set.of("id","revision","digest"));
            if(ref.size()!=3)throw new IllegalArgumentException(); integer(ref,"revision",null);
        }
        var task=n.get("task");fields(task,Set.of("state","generation","updatedAt","error","pendingBatchId"));
        if(task.size()!=5)throw new IllegalArgumentException();integer(task,"generation",null);integer(n,"nextGeneration",null);
        if(!task.get("pendingBatchId").isNull())WorkflowRecoveryJson.canonical(text(task,"pendingBatchId"));
        if(!n.get("schedule").isNull()){
            var schedule=n.get("schedule");fields(schedule,Set.of("state","generation","updatedAt","error","pendingBatchId"));
            if(schedule.size()!=5)throw new IllegalArgumentException();integer(schedule,"generation",null);
            if(!schedule.get("pendingBatchId").isNull())WorkflowRecoveryJson.canonical(text(schedule,"pendingBatchId"));
        }
        return CatalogJson.JSON.treeToValue(n,WorkflowTaskArchive.class);
    }
}
