package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowRecoveryJson {
    private WorkflowRecoveryJson(){}
    public static UUID canonical(String value){if(value==null||!value.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalArgumentException();return UUID.fromString(value);}
    public static WorkflowRecovery.Command command(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("requestId","id","revision","digest","kind","batchId","expectedGeneration","acknowledgeUncertainOutput"));if(n.size()!=8||!n.get("acknowledgeUncertainOutput").isBoolean()||!n.get("acknowledgeUncertainOutput").booleanValue())throw new IllegalArgumentException();return new WorkflowRecovery.Command(canonical(text(n,"requestId")),text(n,"id"),integer(n,"revision",null),text(n,"digest"),WorkflowQuality.Kind.valueOf(text(n,"kind")),canonical(text(n,"batchId")),integer(n,"expectedGeneration",null),true);}
    public static WorkflowRecovery.Receipt decode(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("requestId","commandDigest","reference","kind","batchId","previousGeneration","generation","acceptedAt","state","preservedCursor","confirmedBatches","confirmedRecords"));if(n.size()!=12)throw new IllegalArgumentException();fields(n.get("reference"),Set.of("id","revision","digest"));if(n.get("reference").size()!=3)throw new IllegalArgumentException();canonical(text(n,"requestId"));canonical(text(n,"batchId"));for(var k:List.of("previousGeneration","generation","confirmedBatches","confirmedRecords"))integer(n,k,null);integer(n.get("reference"),"revision",null);try{return CatalogJson.JSON.treeToValue(n,WorkflowRecovery.Receipt.class);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid recovery closure");}}
}
