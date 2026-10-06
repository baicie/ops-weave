package com.acme.opsweave.platform.workflow;
import com.acme.opsweave.integration.domain.WorkflowRuntime.*;
import com.acme.opsweave.integration.domain.WorkflowTaskAuthority;
import com.acme.opsweave.integration.domain.WorkflowRuntimeControl;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowRuntimeJson {
 private WorkflowRuntimeJson() {}
 public static WorkflowRuntimeControl.Receipt control(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("requestId","operation","commandDigest","createdAt","task"));return new WorkflowRuntimeControl.Receipt(UUID.fromString(text(n,"requestId")),WorkflowRuntimeControl.Operation.valueOf(text(n,"operation")),text(n,"commandDigest"),Instant.parse(text(n,"createdAt")),task(n.get("task").toString()));}
 public static Object wire(WorkflowRuntimeControl.Receipt receipt){return Map.of("requestId",receipt.requestId(),"operation",receipt.operation(),"commandDigest",receipt.commandDigest(),"createdAt",receipt.createdAt(),"task",wire(receipt.task()));}
 public static Settings settings(JsonNode n){fields(n,Set.of("identityField","nameField"));return new Settings(text(n,"identityField"),text(n,"nameField"));}
 public static Task task(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("workflowId","revision","digest","settings","generation","state","cursor","cursorId","updatedAt","error","authority"));return new Task(text(n,"workflowId"),integer(n,"revision",null),text(n,"digest"),settings(n.get("settings")),integer(n,"generation",null),text(n,"state"),Instant.parse(text(n,"cursor")),UUID.fromString(text(n,"cursorId")),Instant.parse(text(n,"updatedAt")),nullable(n,"error"),!n.has("authority")||n.get("authority").isNull()?null:authority(n.get("authority")));}
 public static WorkflowTaskAuthority authority(JsonNode n){fields(n,Set.of("id","issuer","externalSubject","grantDigest","issuedAt","expiresAt","maxBatches","consumedBatches"));return new WorkflowTaskAuthority(UUID.fromString(text(n,"id")),text(n,"issuer"),text(n,"externalSubject"),text(n,"grantDigest"),Instant.parse(text(n,"issuedAt")),Instant.parse(text(n,"expiresAt")),integer(n,"maxBatches",null),integer(n,"consumedBatches",null));}
 public static Object wire(Task task){var n=(tools.jackson.databind.node.ObjectNode)CatalogJson.JSON.valueToTree(task);n.remove("authority");if(task.authority()!=null){var a=task.authority();n.set("authorization",CatalogJson.JSON.valueToTree(Map.of("id",a.id(),"issuedAt",a.issuedAt(),"expiresAt",a.expiresAt(),"maxBatches",a.maxBatches(),"consumedBatches",a.consumedBatches())));}return n;}
 public static Execution execution(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("id","workflowId","revision","digest","settings","origin","syncRunId","createdAt","state","accepted","rejected","filtered","entityIds","error","authorizationId"));var ids=new ArrayList<String>();if(!n.get("entityIds").isArray()||n.get("entityIds").size()>5)throw new IllegalArgumentException();for(var id:n.get("entityIds")){if(!id.isString())throw new IllegalArgumentException();ids.add(id.asString());}return new Execution(UUID.fromString(text(n,"id")),text(n,"workflowId"),integer(n,"revision",null),text(n,"digest"),settings(n.get("settings")),text(n,"origin"),n.get("syncRunId").isNull()?null:UUID.fromString(text(n,"syncRunId")),Instant.parse(text(n,"createdAt")),text(n,"state"),integer(n,"accepted",null),integer(n,"rejected",null),integer(n,"filtered",null),ids,nullable(n,"error"),!n.has("authorizationId")||n.get("authorizationId").isNull()?null:UUID.fromString(text(n,"authorizationId")));}
 private static String nullable(JsonNode n,String key){if(!n.has(key))throw new IllegalArgumentException();return n.get(key).isNull()?null:text(n,key);}
}
