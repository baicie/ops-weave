package com.acme.opsweave.platform.telemetry;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.application.WorkflowLogOutputService.OutputFailure;
import com.acme.opsweave.integration.domain.WorkflowLogOutput.*;
import com.acme.opsweave.integration.domain.WorkflowLogOutput.Record;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
/** Explicit protocol Fixture; actual storage is checked separately. */
class ClickHouseWorkflowLogSinkTest{
 HttpServer server;ClickHouseWorkflowLogSink sink;int posts,reads;boolean ackLost,badEngine,oversized,duplicate,redirect;List<Record> visible=List.of();
 final Scope scope=new Scope("logs-fixture","a".repeat(64),UUID.randomUUID(),"logs-fixture",1,"sha256:"+"b".repeat(64));
 final List<Record> records=List.of(new Record(0,"2026-10-04T00:00:00Z","  Fixture quote \" and\n<script>  ",null,"Fixture",null,null),new Record(1,"2026-10-04T00:00:01Z","Fixture second",null,null,null,null));
 @BeforeEach void open()throws Exception{
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{String query=URLDecoder.decode(e.getRequestURI().getRawQuery(),StandardCharsets.UTF_8),body;int status=200;assertEquals("fixture_io",e.getRequestHeaders().getFirst("X-ClickHouse-User"));assertFalse(query.contains("fixture-key"));
   if(query.startsWith("query=SELECT name,type")){var names="tenant_id,owner_scope,request_id,workflow_id,workflow_revision,workflow_digest,row_index,event_time,body,severity_text,service_name,trace_id,span_id".split(",");var types=List.of("String","String","UUID","String","UInt32","String","UInt8","String","String","Nullable(String)","Nullable(String)","Nullable(String)","Nullable(String)");var out=new StringBuilder();for(int i=0;i<names.length;i++)out.append(CatalogJson.JSON.writeValueAsString(Map.of("name",names[i],"type",types.get(i)))).append('\n');body=out.toString();}
   else if(query.startsWith("query=SELECT engine"))body=CatalogJson.JSON.writeValueAsString(Map.of("engine",badEngine?"MergeTree":"ReplacingMergeTree","sorting_key","tenant_id, owner_scope, workflow_id, workflow_revision, request_id, row_index"));
   else if(query.startsWith("query=SELECT row_index FROM"))body="";
   else if(e.getRequestMethod().equals("POST")){posts++;var payload=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertFalse(query.contains("<script>"));assertTrue(query.contains("async_insert=0"));assertEquals(scope.requestId().toString(),CatalogJson.JSON.readTree(payload.split("\n")[0]).get("request_id").asString());assertEquals(records.getFirst().body(),CatalogJson.JSON.readTree(payload.split("\n")[0]).get("body").asString());visible=records;status=ackLost?500:200;body="";}
   else{reads++;assertTrue(query.contains(" FINAL WHERE "));assertTrue(query.contains("{tenant:String}"));assertTrue(query.contains("param_request="+scope.requestId()));var out=new StringBuilder();for(var row:visible){var value=new LinkedHashMap<String,Object>();value.put("row_index",duplicate?0:row.index());value.put("event_time",row.eventTime());value.put("body",row.body());value.put("severity_text",row.severityText());value.put("service_name",row.serviceName());value.put("trace_id",row.traceId());value.put("span_id",row.spanId());out.append(CatalogJson.JSON.writeValueAsString(value)).append('\n');}body=oversized?" ".repeat(65537):out.toString();if(redirect){status=302;e.getResponseHeaders().set("Location","http://127.0.0.1:1/");}}
   var bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);try(var stream=e.getResponseBody()){if(bytes.length>0)stream.write(bytes);}
  });server.start();sink=new ClickHouseWorkflowLogSink(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture_io","fixture-key");
 }
 @AfterEach void close(){server.stop(0);}
 @Test void fixedTableAndTypedParametersPreserveText(){assertTrue(sink.ready());sink.write(new Batch(scope,records));assertEquals(1,posts);assertEquals(0,reads);assertEquals(records,sink.read(scope));assertEquals(1,reads);}
 @Test void lostAckIsUnknownAndNeverRetries(){ackLost=true;assertTrue(assertThrows(OutputFailure.class,()->sink.write(new Batch(scope,records))).unknown());assertEquals(1,posts);assertEquals(records,sink.read(scope));assertEquals(1,posts);}
 @Test void badEngineRejectsBeforePost(){badEngine=true;assertFalse(sink.ready());assertFalse(assertThrows(OutputFailure.class,()->sink.write(new Batch(scope,records))).unknown());assertEquals(0,posts);}
 @Test void malformedOverbudgetAndRedirectReadsFailClosed(){visible=records;duplicate=true;assertThrows(OutputFailure.class,()->sink.read(scope));duplicate=false;oversized=true;assertThrows(OutputFailure.class,()->sink.read(scope));oversized=false;redirect=true;assertThrows(OutputFailure.class,()->sink.read(scope));assertEquals(0,posts);}
 @Test void unsafeConfigurationCannotBecomeCallerSqlOrHttp(){for(var uri:List.of("https://127.0.0.1:8123","http://localhost:8123","http://192.0.2.1:8123","http://user@127.0.0.1:8123","http://127.0.0.1:8123/x","http://127.0.0.1:8123/?query=x"))assertThrows(IllegalArgumentException.class,()->new ClickHouseWorkflowLogSink(URI.create(uri),"fixture_io","fixture-key"));assertThrows(IllegalArgumentException.class,()->new ClickHouseWorkflowLogSink(URI.create("http://127.0.0.1:8123"),"fixture_io","bad\nkey"));}
 @Test void systemProxyIsNotUsed(){var old=ProxySelector.getDefault();var selections=new java.util.concurrent.atomic.AtomicInteger();try{ProxySelector.setDefault(new ProxySelector(){public List<Proxy> select(URI uri){selections.incrementAndGet();return List.of(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",1)));}public void connectFailed(URI uri,SocketAddress addr,java.io.IOException e){}});sink=new ClickHouseWorkflowLogSink(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture_io","fixture-key");}finally{ProxySelector.setDefault(old);}assertTrue(sink.ready());assertEquals(0,selections.get());}
}
