package com.acme.opsweave.platform.workflow;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

public final class WorkflowJson {
 private WorkflowJson() {}
 public static WorkflowDefinition definition(JsonNode n) {
  fields(n,Set.of("schemaVersion","id","revision","name","source","target","nodes","edges"));if(!text(n,"schemaVersion").equals("2.0"))throw new IllegalArgumentException();
  var s=n.get("source");fields(s,Set.of("kind","instanceId"));var t=n.get("target");fields(t,Set.of("id","revision","digest"));
  var nodes=new ArrayList<WorkflowDefinition.Node>();var edges=new ArrayList<WorkflowDefinition.Edge>();
  if(n.get("nodes")==null||!n.get("nodes").isArray()||n.get("nodes").size()>16||n.get("edges")==null||!n.get("edges").isArray()||n.get("edges").size()>15)throw new IllegalArgumentException();
  for(var v:n.get("nodes")){fields(v,Set.of("id","type","version","config"));var config=new LinkedHashMap<String,String>();var c=v.get("config");if(c==null||!c.isObject()||c.size()>32)throw new IllegalArgumentException();for(var e:c.properties()){if(!e.getValue().isString())throw new IllegalArgumentException();config.put(e.getKey(),e.getValue().asString());}nodes.add(new WorkflowDefinition.Node(text(v,"id"),WorkflowDefinition.Type.valueOf(text(v,"type")),text(v,"version"),config));}
  for(var e:n.get("edges")){fields(e,Set.of("from","to"));edges.add(new WorkflowDefinition.Edge(text(e,"from"),text(e,"to")));}
  var d=new WorkflowDefinition(text(n,"id"),integer(n,"revision",null),text(n,"name"),new WorkflowDefinition.Source(text(s,"kind"),text(s,"instanceId")),new WorkflowDefinition.Target(text(t,"id"),integer(t,"revision",null),text(t,"digest")),nodes,edges);
  if(CatalogJson.JSON.writeValueAsBytes(wire(d)).length>16384)throw new IllegalArgumentException();return d;
 }
 public static Map<String,Object> wire(WorkflowDefinition d){return Map.of("schemaVersion","2.0","id",d.id(),"revision",d.revision(),"name",d.name(),"source",d.source(),"target",d.target(),"nodes",d.nodes(),"edges",d.edges());}
 public static Map<String,Position> layout(JsonNode n){if(n==null||!n.isObject()||n.size()>16)throw new IllegalArgumentException();var positions=new LinkedHashMap<String,Position>();for(var e:n.properties()){fields(e.getValue(),Set.of("x","y"));positions.put(e.getKey(),new Position(integer(e.getValue(),"x",null),integer(e.getValue(),"y",null)));}return positions;}
 public static Map<String,Object> wire(Entry e){var v=new LinkedHashMap<String,Object>();v.put("definition",wire(e.definition()));v.put("digest",e.digest());v.put("state",e.state());v.put("editVersion",e.editVersion());v.put("layout",e.layout());v.put("updatedAt",e.updatedAt().toString());v.put("preview",e.preview());return v;}
 public static String encode(Entry e){return CatalogJson.JSON.writeValueAsString(wire(e));}
 public static Entry decode(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("definition","digest","state","editVersion","layout","updatedAt","preview"));return new Entry(definition(n.get("definition")),text(n,"digest"),text(n,"state"),integer(n,"editVersion",null),layout(n.get("layout")),Instant.parse(text(n,"updatedAt")),n.get("preview").isNull()?null:receipt(n.get("preview")));}
 public static Receipt receipt(JsonNode n){fields(n,Set.of("id","digest","inputDigest","origin","accepted","rejected","filtered","createdAt"));return new Receipt(UUID.fromString(text(n,"id")),text(n,"digest"),text(n,"inputDigest"),text(n,"origin"),integer(n,"accepted",null),integer(n,"rejected",null),integer(n,"filtered",null),Instant.parse(text(n,"createdAt")));}
 public static Map<String,Object> summary(Run r){return Map.of("workflowId",r.workflowId(),"revision",r.revision(),"mode",r.mode(),"receipt",r.receipt());}
 public static Object detail(Run r){var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","2.0");result.put("run",summary(r));result.put("trace",r.trace());return result;}
 public static Run run(String json){var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("workflowId","revision","mode","receipt","trace"));return new Run(text(n,"workflowId"),integer(n,"revision",null),text(n,"mode"),receipt(n.get("receipt")),n.has("trace")&&!n.get("trace").isNull()?trace(n.get("trace")):null);}
 private static long nonnegative(JsonNode n,String key){var v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.asLong()<0||v.asLong()>9007199254740991L)throw new IllegalArgumentException();return v.asLong();}
 private static boolean bool(JsonNode n,String key){var v=n.get(key);if(v==null||!v.isBoolean())throw new IllegalArgumentException();return v.asBoolean();}
 private static WorkflowTrace trace(JsonNode n){
  fields(n,Set.of("source","target","syncRunId","startedAt","durationMillis","retainedCount","missingRaw","truncated","sourceStatus","dryRun","writesPerformed","rows"));
  var source=n.get("source");fields(source,Set.of("kind","instanceId"));var target=n.get("target");fields(target,Set.of("id","revision","digest"));
  var rows=new ArrayList<WorkflowTrace.Row>();var raw=n.get("rows");if(raw==null||!raw.isArray()||raw.size()>5)throw new IllegalArgumentException();
  for(var row:raw){fields(row,Set.of("index","status","steps"));var steps=new ArrayList<WorkflowTrace.Step>();var rawSteps=row.get("steps");if(rawSteps==null||!rawSteps.isArray()||rawSteps.size()>16)throw new IllegalArgumentException();
   for(var step:rawSteps){fields(step,Set.of("nodeId","type","status","issues"));var issues=new ArrayList<com.acme.opsweave.catalog.domain.ModelPreview.Issue>();var rawIssues=step.get("issues");if(rawIssues==null||!rawIssues.isArray()||rawIssues.size()>64)throw new IllegalArgumentException();for(var issue:rawIssues){fields(issue,Set.of("field","code"));issues.add(new com.acme.opsweave.catalog.domain.ModelPreview.Issue(text(issue,"field"),text(issue,"code")));}steps.add(new WorkflowTrace.Step(text(step,"nodeId"),WorkflowDefinition.Type.valueOf(text(step,"type")),text(step,"status"),issues));}
   rows.add(new WorkflowTrace.Row(integer(row,"index",null),text(row,"status"),steps));
  }
  if(!n.has("syncRunId"))throw new IllegalArgumentException();return new WorkflowTrace(new WorkflowDefinition.Source(text(source,"kind"),text(source,"instanceId")),new WorkflowDefinition.Target(text(target,"id"),integer(target,"revision",null),text(target,"digest")),n.get("syncRunId").isNull()?null:UUID.fromString(text(n,"syncRunId")),Instant.parse(text(n,"startedAt")),nonnegative(n,"durationMillis"),nonnegative(n,"retainedCount"),nonnegative(n,"missingRaw"),bool(n,"truncated"),text(n,"sourceStatus"),bool(n,"dryRun"),bool(n,"writesPerformed"),rows);
 }
 public static List<Map<String,Object>> samples(JsonNode n){if(n==null||!n.isArray()||n.isEmpty()||n.size()>5)throw new IllegalArgumentException();var values=new ArrayList<Map<String,Object>>();for(var s:n){var v=CatalogJson.sample(s);WorkflowEvaluation.bounded(v);values.add(v);}return values;}
}
