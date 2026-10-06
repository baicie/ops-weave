package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.WorkflowDiagnostics;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.util.Set;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowDiagnosticsJson {
    private WorkflowDiagnosticsJson() {}
    public static WorkflowDiagnostics.Stored decode(String json){
        var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("observation","entityIds"));if(n.size()!=2||!n.get("entityIds").isArray()||n.get("entityIds").size()>5)throw new IllegalArgumentException();
        var o=n.get("observation");fields(o,Set.of("id","reference","kind","generation","state","from","till","startedAt","completedAt","queueWaitMillis","relatedBatchId","error","result","sourceRead","dispatch"));if(o.size()!=13+(o.has("sourceRead")?1:0)+(o.has("dispatch")?1:0))throw new IllegalArgumentException();
        fields(o.get("reference"),Set.of("id","revision","digest"));if(o.get("reference").size()!=3)throw new IllegalArgumentException();
        var r=o.get("result");fields(r,Set.of("coverage","sampleRate","received","accepted","rejected","filtered","unknown","schemaMismatch","missingIdentity","invalidTimestamp","unitMismatch","nodes"));if(r.size()!=12||!r.get("nodes").isArray()||r.get("nodes").size()>16)throw new IllegalArgumentException();
        for(var k:Set.of("received","accepted","rejected","filtered","unknown","schemaMismatch","missingIdentity","invalidTimestamp","unitMismatch"))if(!r.get(k).isNull())integer(r,k,null);
        integer(o,"generation",null);integer(o.get("reference"),"revision",null);
        if(!r.get("sampleRate").isNull())throw new IllegalArgumentException();if(!o.get("queueWaitMillis").isNull())integer(o,"queueWaitMillis",null);
        if(o.has("dispatch")&&!o.get("dispatch").isNull()){var dispatch=o.get("dispatch");fields(dispatch,Set.of("id","enqueuedAt","startedAt"));if(dispatch.size()!=3||!text(dispatch,"id").matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalArgumentException();for(var key:Set.of("enqueuedAt","startedAt"))WorkflowSampleRecoveryJson.instant(text(dispatch,key));}
        if(o.has("sourceRead")&&!o.get("sourceRead").isNull()){var source=o.get("sourceRead");fields(source,Set.of("attempts","completed","failed","received","failureCode","startedAt","completedAt"));if(source.size()!=7)throw new IllegalArgumentException();for(var key:Set.of("attempts","completed","failed"))integer(source,key,null);if(!source.get("received").isNull())integer(source,"received",null);for(var key:Set.of("startedAt","completedAt")){var value=text(source,key);com.acme.opsweave.platform.workflow.WorkflowSampleRecoveryJson.instant(value);}}
        for(var node:r.get("nodes")){fields(node,Set.of("nodeId","type","received","accepted","rejected","filtered","skipped","issues"));if(node.size()!=8||!node.get("issues").isObject())throw new IllegalArgumentException();for(var k:Set.of("received","accepted","rejected","filtered","skipped"))integer(node,k,null);for(var entry:node.get("issues").properties())if(!entry.getValue().isIntegralNumber())throw new IllegalArgumentException();}
        try{return CatalogJson.JSON.treeToValue(n,WorkflowDiagnostics.Stored.class);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid validation metadata");}
    }
    public static Object wire(WorkflowDiagnostics.Observation observation){var value=CatalogJson.JSON.convertValue(observation,java.util.LinkedHashMap.class);if(observation.sourceRead()==null)value.remove("sourceRead");if(observation.dispatch()==null)value.remove("dispatch");return value;}
    public static Object wire(WorkflowDiagnostics.Report report){var value=new java.util.LinkedHashMap<String,Object>();value.put("schemaVersion",report.schemaVersion());value.put("asOf",report.asOf());value.put("reference",report.reference());value.put("observations",report.observations().stream().map(WorkflowDiagnosticsJson::wire).toList());value.put("truncated",report.truncated());return value;}
}
