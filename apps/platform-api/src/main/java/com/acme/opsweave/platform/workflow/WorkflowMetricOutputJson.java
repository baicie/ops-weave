package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.WorkflowMetricOutput.Receipt;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.util.Set;
import static com.acme.opsweave.platform.integration.PipelineJson.fields;

public final class WorkflowMetricOutputJson {
    private WorkflowMetricOutputJson() {}
    public static Receipt decode(String value) {
        var node=CatalogJson.JSON.readTree(value);
        fields(node,Set.of("requestId","workflowId","revision","digest","previewId","commandDigest","createdAt","updatedAt","state","accepted","filtered","collapsed","confirmed","failed","unknown","error","labels","seriesHash","batchDigest","timestamps"));
        if(node.size()!=20)throw new IllegalArgumentException();
        for(var key:Set.of("accepted","revision","filtered","collapsed","confirmed","failed","unknown"))com.acme.opsweave.platform.integration.PipelineJson.integer(node,key,null);
        for(var key:Set.of("requestId","workflowId","digest","previewId","commandDigest","createdAt","updatedAt","state","seriesHash","batchDigest"))com.acme.opsweave.platform.integration.PipelineJson.text(node,key);
        if(!node.get("labels").isObject()||!node.get("timestamps").isArray()||!node.get("error").isNull()&&!node.get("error").isString())throw new IllegalArgumentException();
        for(var field:node.get("labels").properties())if(!field.getValue().isString())throw new IllegalArgumentException();
        for(var time:node.get("timestamps"))if(!time.isIntegralNumber()||!time.canConvertToLong())throw new IllegalArgumentException();
        return CatalogJson.JSON.readValue(value,Receipt.class);
    }
}
