package com.acme.opsweave.platform.telemetry;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.application.WorkflowMetricOutputService.OutputFailure;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

/** Synthetic protocol responses. These tests do not claim writes to a real time-series service. */
class VictoriaWorkflowMetricSinkTest {
    HttpServer server;VictoriaWorkflowMetricSink sink;int writes,reads;boolean ackLost,partial,wrongLabels,badFlags,oversized;
    final Map<String,String> labels=Map.of("tenant_id","workflow-output-fixture","metric_key","host.cpu.utilization.user");
    final List<MetricWriteBatch.Sample> samples=List.of(new MetricWriteBatch.Sample(1791072000000L,new BigDecimal("0.125")),new MetricWriteBatch.Sample(1791072001000L,new BigDecimal("0.2")));
    List<MetricWriteBatch.Sample> visible=List.of();
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath(),body;int status=200;
            if(path.equals("/flags"))body="dedup.minScrapeInterval="+(badFlags?"1s":"1ms")+"\ninflux.forceStreamMode=false\n";
            else if(path.equals("/write")){
                writes++;assertEquals("POST",exchange.getRequestMethod());assertEquals("precision=ms",exchange.getRequestURI().getQuery());assertEquals("0",exchange.getRequestHeaders().getFirst("Stream-Mode"));
                String payload=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertTrue(payload.contains("opsweave_workflow_metric,metric_key=host.cpu.utilization.user,tenant_id=workflow-output-fixture value=0.125 1791072000000"));
                visible=partial?samples.subList(0,1):samples;status=ackLost?500:204;body="";
            }else{
                assertEquals("/api/v1/export",path);reads++;assertTrue(exchange.getRequestURI().getQuery().contains("opsweave_workflow_metric_value"));
                var metric=new HashMap<>(labels);metric.put("__name__","opsweave_workflow_metric_value");if(wrongLabels)metric.put("tenant_id","foreign-fixture");
                body=visible.isEmpty()?"":CatalogJson.JSON.writeValueAsString(Map.of("metric",metric,"timestamps",visible.stream().map(MetricWriteBatch.Sample::timestampMillis).toList(),"values",visible.stream().map(MetricWriteBatch.Sample::value).toList()))+"\n";
                if(oversized)body=" ".repeat(2*1024*1024+1);
            }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,status==204?-1:bytes.length);try(var out=exchange.getResponseBody()){if(status!=204)out.write(bytes);}
        });server.start();sink=new VictoriaWorkflowMetricSink(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
    }
    @AfterEach void close(){server.stop(0);}
    @Test void confirmationRequiresExactReadbackAndReplaySkipsWrite(){sink.write(new MetricWriteBatch(labels,samples,0));assertEquals(1,writes);assertEquals(2,reads);sink.write(new MetricWriteBatch(labels,samples,0));assertEquals(1,writes);assertEquals(samples,sink.read(labels,samples.stream().map(MetricWriteBatch.Sample::timestampMillis).toList()));}
    @Test void lostAcknowledgementNeverRetriesPostAndCanBeRead(){ackLost=true;assertTrue(assertThrows(OutputFailure.class,()->sink.write(new MetricWriteBatch(labels,samples,0))).unknown());assertEquals(1,writes);assertEquals(1,reads);assertEquals(samples,sink.read(labels,samples.stream().map(MetricWriteBatch.Sample::timestampMillis).toList()));assertEquals(1,writes);}
    @Test void parsingAckDoesNotConfirmPartialArrival(){partial=true;assertTrue(assertThrows(OutputFailure.class,()->sink.write(new MetricWriteBatch(labels,samples,0))).unknown());assertEquals(1,writes);assertEquals(2,reads);assertEquals(1,sink.read(labels,samples.stream().map(MetricWriteBatch.Sample::timestampMillis).toList()).size());assertEquals(1,writes);}
    @Test void conflictingExistingValueAndUnsafeConfigurationRejectBeforePost(){visible=List.of(new MetricWriteBatch.Sample(samples.getFirst().timestampMillis(),new BigDecimal("0.15")));assertFalse(assertThrows(OutputFailure.class,()->sink.write(new MetricWriteBatch(labels,samples,0))).unknown());assertEquals(0,writes);badFlags=true;assertFalse(assertThrows(OutputFailure.class,()->sink.write(new MetricWriteBatch(labels,samples,0))).unknown());assertEquals(0,writes);}
    @Test void foreignLabelsAndOversizedResponsesFailClosed(){visible=samples;wrongLabels=true;assertThrows(OutputFailure.class,()->sink.read(labels,samples.stream().map(MetricWriteBatch.Sample::timestampMillis).toList()));wrongLabels=false;oversized=true;assertThrows(OutputFailure.class,()->sink.read(labels,samples.stream().map(MetricWriteBatch.Sample::timestampMillis).toList()));assertEquals(0,writes);}
    @Test void constructorRejectsArbitraryDestinations(){for(String uri:List.of("https://127.0.0.1:18428","http://localhost:18428","http://192.0.2.1:18428","http://user@127.0.0.1:18428","http://127.0.0.1:18428/custom"))assertThrows(IllegalArgumentException.class,()->new VictoriaWorkflowMetricSink(URI.create(uri)));}
    @Test void loopbackOutputDoesNotUseTheSystemProxy(){
        var original=ProxySelector.getDefault();var selections=new java.util.concurrent.atomic.AtomicInteger();
        try{ProxySelector.setDefault(new ProxySelector(){public List<Proxy> select(URI uri){selections.incrementAndGet();return List.of(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",1)));}public void connectFailed(URI uri,SocketAddress address,java.io.IOException failure){}});sink=new VictoriaWorkflowMetricSink(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));}
        finally{ProxySelector.setDefault(original);}
        sink.write(new MetricWriteBatch(labels,samples,0));assertEquals(1,writes);assertEquals(0,selections.get());
    }
}
