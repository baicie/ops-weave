package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.integration.application.WorkflowLogOutputService.*;
import com.acme.opsweave.integration.domain.WorkflowLogOutput.*;
import com.acme.opsweave.integration.domain.WorkflowLogOutput.Record;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** A fixed table and typed parameters; no caller SQL, redirects, proxy, retries or DDL. */
public final class ClickHouseWorkflowLogSink implements Sink {
 private static final String TABLE="opsweave_logs.workflow_records";
 private static final String COLUMNS="tenant_id,owner_scope,request_id,workflow_id,workflow_revision,workflow_digest,row_index,event_time,body,severity_text,service_name,trace_id,span_id";
 private static final List<String> TYPES=List.of("String","String","UUID","String","UInt32","String","UInt8","String","String","Nullable(String)","Nullable(String)","Nullable(String)","Nullable(String)");
 private final BoundedClickHouseLogTransport transport;private final JsonMapper json=JsonMapper.builder().build();
 public ClickHouseWorkflowLogSink(URI origin,String user,String key){transport=new BoundedClickHouseLogTransport(origin,user,key,65536);}
 private List<tools.jackson.databind.JsonNode> rows(String body){return transport.rows(body,16);}
 private static String text(tools.jackson.databind.JsonNode row,String name){return BoundedClickHouseLogTransport.text(row,name);}
 private static String nullable(tools.jackson.databind.JsonNode row,String name){return BoundedClickHouseLogTransport.nullable(row,name);}
 private void configuration(){transport.configuration("workflow_records",COLUMNS,TYPES,"tenant_id,owner_scope,workflow_id,workflow_revision,request_id,row_index");}
 @Override public boolean ready(){try{configuration();return true;}catch(OutputFailure unavailable){return false;}}
 @Override public void write(Batch batch){
  configuration();var scope=batch.scope();var body=new StringBuilder();try{for(var row:batch.records()){var values=new LinkedHashMap<String,Object>();values.put("tenant_id",scope.tenant());values.put("owner_scope",scope.ownerScope());values.put("request_id",scope.requestId());values.put("workflow_id",scope.workflowId());values.put("workflow_revision",scope.revision());values.put("workflow_digest",scope.digest());values.put("row_index",row.index());values.put("event_time",row.eventTime());values.put("body",row.body());values.put("severity_text",row.severityText());values.put("service_name",row.serviceName());values.put("trace_id",row.traceId());values.put("span_id",row.spanId());body.append(json.writeValueAsString(values)).append('\n');}}catch(RuntimeException invalid){throw new OutputFailure(false);}
  if(body.toString().getBytes(StandardCharsets.UTF_8).length>65536)throw new OutputFailure(false);
  transport.request("INSERT INTO "+TABLE+" ("+COLUMNS+") SETTINGS max_execution_time=3,async_insert=0 FORMAT JSONEachRow",Map.of(),body.toString(),true);
 }
 @Override public List<Record> read(Scope scope){
  var params=Map.of("tenant",scope.tenant(),"owner",scope.ownerScope(),"request",scope.requestId().toString(),"workflow",scope.workflowId(),"revision",Integer.toString(scope.revision()),"digest",scope.digest());
  var sql="SELECT row_index,event_time,body,severity_text,service_name,trace_id,span_id FROM "+TABLE+" FINAL WHERE tenant_id={tenant:String} AND owner_scope={owner:String} AND request_id={request:UUID} AND workflow_id={workflow:String} AND workflow_revision={revision:UInt32} AND workflow_digest={digest:String} ORDER BY row_index LIMIT 6 SETTINGS max_execution_time=3,max_result_rows=6,result_overflow_mode='throw',max_result_bytes=65536 FORMAT JSONEachRow";
  var result=new ArrayList<Record>();int previous=-1;try{for(var row:rows(transport.request(sql,params,null,false))){if(row.size()!=7)throw new IllegalArgumentException();var index=row.get("row_index");if(index==null||!index.isIntegralNumber()||!index.canConvertToInt()||index.asInt()<=previous)throw new IllegalArgumentException();previous=index.asInt();result.add(new Record(previous,text(row,"event_time"),text(row,"body"),nullable(row,"severity_text"),nullable(row,"service_name"),nullable(row,"trace_id"),nullable(row,"span_id")));if(result.size()>5)throw new IllegalArgumentException();}return List.copyOf(result);}catch(RuntimeException invalid){throw new OutputFailure(false);}
 }
}
