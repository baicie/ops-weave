package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.integration.api.WorkflowLogWindowSink;
import com.acme.opsweave.integration.application.WorkflowLogOutputService.OutputFailure;
import com.acme.opsweave.integration.domain.WorkflowLogOutput.Scope;
import com.acme.opsweave.integration.domain.WorkflowLogWindow;
import com.acme.opsweave.integration.domain.WorkflowLogWindow.*;
import com.acme.opsweave.integration.domain.WorkflowLogWindow.Record;
import java.net.URI;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static com.acme.opsweave.platform.telemetry.BoundedClickHouseLogTransport.*;

/** A build-time fixed table; the entire bounded window is one POST, then independently verified. */
public final class ClickHouseWorkflowLogWindowSink implements WorkflowLogWindowSink {
    private final String table;
    private static final String COLUMNS="tenant_id,owner_scope,request_id,workflow_id,workflow_revision,workflow_digest,row_index,source_position,event_time,body,severity_text,service_name,trace_id,span_id";
    private static final List<String> TYPES=List.of("String","String","UUID","String","UInt32","String","UInt32","String","String","String","Nullable(String)","Nullable(String)","Nullable(String)","Nullable(String)");
    private final BoundedClickHouseLogTransport transport;private final JsonMapper json=JsonMapper.builder().build();
    public ClickHouseWorkflowLogWindowSink(URI origin,String user,String key){this(origin,user,key,false);}
    private ClickHouseWorkflowLogWindowSink(URI origin,String user,String key,boolean replay){table=replay?"workflow_log_replays":"workflow_log_windows";transport=new BoundedClickHouseLogTransport(origin,user,key,8*1024*1024);}
    public static ClickHouseWorkflowLogWindowSink replay(URI origin,String user,String key){return new ClickHouseWorkflowLogWindowSink(origin,user,key,true);}
    private void configuration(){transport.configuration(table,COLUMNS,TYPES,"tenant_id,owner_scope,workflow_id,workflow_revision,request_id,row_index");}
    @Override public boolean ready(){try{configuration();return true;}catch(OutputFailure unavailable){return false;}}
    @Override public void write(Batch batch){
        if(batch.records().isEmpty())throw new OutputFailure(false);configuration();var scope=batch.scope();var body=new StringBuilder();
        try{for(var row:batch.records()){var values=new LinkedHashMap<String,Object>();values.put("tenant_id",scope.tenant());values.put("owner_scope",scope.ownerScope());values.put("request_id",scope.requestId());values.put("workflow_id",scope.workflowId());values.put("workflow_revision",scope.revision());values.put("workflow_digest",scope.digest());values.put("row_index",row.index());values.put("source_position",row.position());values.put("event_time",row.eventTime());values.put("body",row.body());values.put("severity_text",row.severityText());values.put("service_name",row.serviceName());values.put("trace_id",row.traceId());values.put("span_id",row.spanId());body.append(json.writeValueAsString(values)).append('\n');}}
        catch(RuntimeException invalid){throw new OutputFailure(false);}
        transport.request("INSERT INTO opsweave_logs."+table+" ("+COLUMNS+") SETTINGS max_execution_time=3,async_insert=0 FORMAT JSONEachRow",Map.of(),body.toString(),true);
    }
    @Override public List<Record> read(Scope scope){
        var params=Map.of("tenant",scope.tenant(),"owner",scope.ownerScope(),"request",scope.requestId().toString(),"workflow",scope.workflowId(),"revision",Integer.toString(scope.revision()),"digest",scope.digest());
        var sql="SELECT row_index,source_position,event_time,body,severity_text,service_name,trace_id,span_id FROM opsweave_logs."+table+" FINAL WHERE tenant_id={tenant:String} AND owner_scope={owner:String} AND request_id={request:UUID} AND workflow_id={workflow:String} AND workflow_revision={revision:UInt32} AND workflow_digest={digest:String} ORDER BY row_index LIMIT 1001 SETTINGS max_execution_time=3,max_result_rows=1001,result_overflow_mode='throw',max_result_bytes=8388608 FORMAT JSONEachRow";
        try{var result=new ArrayList<Record>();int previous=-1;java.time.Instant last=null;
            for(var row:transport.rows(transport.request(sql,params,null,false),1001)){
                if(row.size()!=8)throw new IllegalArgumentException();var index=row.get("row_index");if(index==null||!index.isIntegralNumber()||!index.canConvertToInt()||index.asInt()<=previous)throw new IllegalArgumentException();previous=index.asInt();
                var record=new Record(previous,text(row,"source_position"),text(row,"event_time"),text(row,"body"),nullable(row,"severity_text"),nullable(row,"service_name"),nullable(row,"trace_id"),nullable(row,"span_id"));var position=java.time.Instant.parse(record.position());
                if(last!=null&&!position.isAfter(last)||result.size()>=WorkflowLogWindow.MAX_RECORDS)throw new IllegalArgumentException();last=position;result.add(record);
            }return List.copyOf(result);
        }catch(RuntimeException invalid){throw new OutputFailure(false);}
    }
}
