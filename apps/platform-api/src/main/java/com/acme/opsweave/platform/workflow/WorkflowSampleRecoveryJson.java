package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowSampleRecoveryJson {
    private WorkflowSampleRecoveryJson(){}
    public static Instant instant(String value){try{if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{3}([0-9]{3}([0-9]{3})?)?)?Z"))throw new IllegalArgumentException();var time=Instant.parse(value);if(time.getEpochSecond()<0||!time.toString().equals(value))throw new IllegalArgumentException();return time;}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("Invalid sample proof time");}}
    public static WorkflowSampleRecovery.Command command(String json){try{return parseCommand(json);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid sample closure command");}}
    private static WorkflowSampleRecovery.Command parseCommand(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("requestId","id","revision","digest","kind","batchId","batchDigest","expectedUpdatedAt","acknowledgeUncertainOutput"));if(n.size()!=9||!n.get("acknowledgeUncertainOutput").isBoolean()||!n.get("acknowledgeUncertainOutput").booleanValue())throw new IllegalArgumentException();return new WorkflowSampleRecovery.Command(WorkflowRecoveryJson.canonical(text(n,"requestId")),text(n,"id"),integer(n,"revision",null),text(n,"digest"),WorkflowSampleRecovery.Kind.valueOf(text(n,"kind")),WorkflowRecoveryJson.canonical(text(n,"batchId")),text(n,"batchDigest"),instant(text(n,"expectedUpdatedAt")),true);}
    public static WorkflowSampleRecovery.Receipt decode(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("schemaVersion","requestId","commandDigest","reference","kind","batchId","batchDigest","proofUpdatedAt","acceptedAt","state","uncertainRecords"));if(n.size()!=11)throw new IllegalArgumentException();var ref=n.get("reference");fields(ref,Set.of("id","revision","digest"));if(ref.size()!=3)throw new IllegalArgumentException();return new WorkflowSampleRecovery.Receipt(text(n,"schemaVersion"),WorkflowRecoveryJson.canonical(text(n,"requestId")),text(n,"commandDigest"),new WorkflowQuality.Reference(text(ref,"id"),integer(ref,"revision",null),text(ref,"digest")),WorkflowSampleRecovery.Kind.valueOf(text(n,"kind")),WorkflowRecoveryJson.canonical(text(n,"batchId")),text(n,"batchDigest"),instant(text(n,"proofUpdatedAt")),instant(text(n,"acceptedAt")),text(n,"state"),integer(n,"uncertainRecords",null));}
}
