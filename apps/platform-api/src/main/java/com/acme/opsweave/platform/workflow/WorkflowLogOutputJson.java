package com.acme.opsweave.platform.workflow;
import com.acme.opsweave.integration.domain.WorkflowLogOutput.Receipt;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.util.Set;
import static com.acme.opsweave.platform.integration.PipelineJson.*;
public final class WorkflowLogOutputJson {
 private WorkflowLogOutputJson(){}
 public static Receipt decode(String body){var n=CatalogJson.JSON.readTree(body);fields(n,Set.of("requestId","workflowId","revision","digest","previewId","commandDigest","createdAt","updatedAt","state","accepted","filtered","confirmed","failed","unknown","error","batchDigest","indices"));if(n.size()!=17)throw new IllegalArgumentException();for(var key:Set.of("revision","accepted","filtered","confirmed","failed","unknown"))integer(n,key,null);for(var key:Set.of("requestId","workflowId","digest","previewId","commandDigest","createdAt","updatedAt","state","batchDigest"))text(n,key);if(!n.get("indices").isArray()||!n.get("error").isNull()&&!n.get("error").isString())throw new IllegalArgumentException();for(var index:n.get("indices"))if(!index.isIntegralNumber()||!index.canConvertToInt())throw new IllegalArgumentException();return CatalogJson.JSON.readValue(body,Receipt.class);}
}
