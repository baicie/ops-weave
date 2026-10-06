package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.integration.application.WorkflowLogOutputService.OutputFailure;
import com.acme.opsweave.integration.domain.WorkflowLogOutput.Scope;
import com.acme.opsweave.integration.domain.WorkflowLogWindow.*;
import com.acme.opsweave.integration.domain.WorkflowLogWindow.Record;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit wire fixture; actual provider and storage acceptance is separate. */
class ClickHouseWorkflowLogWindowSinkTest {
    HttpServer server;ClickHouseWorkflowLogWindowSink sink;int posts,reads;boolean ackLost,badType,duplicate,oversized,redirect,duplicateJson;
    final Scope scope=new Scope("window-fixture","a".repeat(64),UUID.randomUUID(),"window-fixture",1,"sha256:"+"b".repeat(64));
    final Instant from=Instant.parse("2026-10-04T00:00:00Z");
    final List<Record> records=java.util.stream.IntStream.range(0,1000).mapToObj(i->new Record(i,from.plusNanos(i).toString(),from.plusNanos(i).toString(),"  Synthetic Fixture "+i+"\n<script>  ",null,null,null,null)).toList();
    List<Record> visible=List.of();
    @BeforeEach void open()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{String query=URLDecoder.decode(e.getRequestURI().getRawQuery(),StandardCharsets.UTF_8),body;int status=200;
            assertEquals("fixture_io",e.getRequestHeaders().getFirst("X-ClickHouse-User"));assertFalse(query.contains("fixture-key"));
            if(query.startsWith("query=SELECT name,type")){assertTrue(query.contains("workflow_log_windows"));var names="tenant_id,owner_scope,request_id,workflow_id,workflow_revision,workflow_digest,row_index,source_position,event_time,body,severity_text,service_name,trace_id,span_id".split(",");var types=List.of("String","String","UUID","String","UInt32","String",badType?"UInt8":"UInt32","String","String","String","Nullable(String)","Nullable(String)","Nullable(String)","Nullable(String)");var out=new StringBuilder();for(int i=0;i<names.length;i++)out.append(CatalogJson.JSON.writeValueAsString(Map.of("name",names[i],"type",types.get(i)))).append('\n');body=out.toString();}
            else if(query.startsWith("query=SELECT engine"))body=CatalogJson.JSON.writeValueAsString(Map.of("engine","ReplacingMergeTree","sorting_key","tenant_id, owner_scope, workflow_id, workflow_revision, request_id, row_index"));
            else if(query.startsWith("query=SELECT row_index FROM"))body="";
            else if(e.getRequestMethod().equals("POST")){posts++;assertTrue(query.contains("async_insert=0"));assertTrue(query.contains("workflow_log_windows"));assertFalse(query.contains("<script>"));var payload=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8).split("\n");assertEquals(1000,payload.length);for(int i=0;i<payload.length;i++){var row=CatalogJson.JSON.readTree(payload[i]);assertEquals(14,row.size());assertEquals(i,row.get("row_index").asInt());assertEquals(records.get(i).position(),row.get("source_position").asString());assertEquals(records.get(i).body(),row.get("body").asString());}visible=records;status=ackLost?500:200;body="";}
            else{reads++;assertTrue(query.contains(" FINAL WHERE "));assertTrue(query.contains("{tenant:String}"));assertTrue(query.contains("param_request="+scope.requestId()));assertTrue(query.contains("LIMIT 1001"));var out=new StringBuilder();for(var row:visible){var values=new LinkedHashMap<String,Object>();values.put("row_index",duplicate?0:row.index());values.put("source_position",row.position());values.put("event_time",row.eventTime());values.put("body",row.body());values.put("severity_text",row.severityText());values.put("service_name",row.serviceName());values.put("trace_id",row.traceId());values.put("span_id",row.spanId());out.append(CatalogJson.JSON.writeValueAsString(values)).append('\n');}body=oversized?" ".repeat(8*1024*1024+1):out.toString();if(duplicateJson)body=body.replaceFirst("\\{","{\"row_index\":0,");if(redirect){status=302;e.getResponseHeaders().set("Location","http://127.0.0.1:1/");}}
            var bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);try(var stream=e.getResponseBody()){if(bytes.length>0)stream.write(bytes);}
        });server.start();sink=new ClickHouseWorkflowLogWindowSink(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture_io","fixture-key");
    }
    @AfterEach void close(){server.stop(0);}
    @Test void entireWindowIsOnePostWithExactScopedReadback(){assertTrue(sink.ready());sink.write(new Batch(scope,from,from.plusSeconds(60),1000,0,records));assertEquals(1,posts);assertEquals(records,sink.read(scope));assertEquals(1,reads);}
    @Test void lostAckRemainsUnknownWithoutRetry(){ackLost=true;assertTrue(assertThrows(OutputFailure.class,()->sink.write(new Batch(scope,from,from.plusSeconds(60),1000,0,records))).unknown());assertEquals(records,sink.read(scope));assertEquals(1,posts);}
    @Test void missingMigrationAndEmptyOutputCannotCausePost(){badType=true;assertFalse(sink.ready());assertFalse(assertThrows(OutputFailure.class,()->sink.write(new Batch(scope,from,from.plusSeconds(60),1000,0,records))).unknown());assertEquals(0,posts);badType=false;assertThrows(OutputFailure.class,()->sink.write(new Batch(scope,from,from.plusSeconds(60),0,0,List.of())));assertEquals(0,posts);}
    @Test void duplicateAndOverbudgetRepliesFailClosed(){visible=records;duplicate=true;assertThrows(OutputFailure.class,()->sink.read(scope));duplicate=false;duplicateJson=true;assertThrows(OutputFailure.class,()->sink.read(scope));duplicateJson=false;oversized=true;assertThrows(OutputFailure.class,()->sink.read(scope));oversized=false;redirect=true;assertThrows(OutputFailure.class,()->sink.read(scope));assertEquals(0,posts);}
    @Test void configurationCannotEscapeLoopbackOrBecomeCallerSql(){for(var uri:List.of("https://127.0.0.1:8123","http://localhost:8123","http://192.0.2.1:8123","http://user@127.0.0.1:8123","http://127.0.0.1:8123/x","http://127.0.0.1:8123/?query=x"))assertThrows(IllegalArgumentException.class,()->new ClickHouseWorkflowLogWindowSink(URI.create(uri),"fixture_io","fixture-key"));}
}
