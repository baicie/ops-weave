package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.integration.application.WorkflowMetricOutputService.*;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Fixed loopback protocol and stable series. Never retries POST or trusts a parsing acknowledgement. */
public final class VictoriaWorkflowMetricSink implements Sink {
    private final URI origin;
    private final Duration confirmationDelay;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector(){
        @Override public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
        @Override public void connectFailed(URI uri,SocketAddress address,java.io.IOException failure){}
    }).build();
    private final JsonMapper json=JsonMapper.builder().build();
    public VictoriaWorkflowMetricSink(URI origin){this(origin,Duration.ZERO);}
    /** One explicit settling period and one readback; no repeated queries or POST retry. */
    public VictoriaWorkflowMetricSink(URI origin,Duration confirmationDelay) {
        if(confirmationDelay==null||confirmationDelay.isNegative()||confirmationDelay.compareTo(Duration.ofSeconds(5))>0)throw new IllegalArgumentException("Bounded metric confirmation delay required");
        this.confirmationDelay=confirmationDelay;
        if(!"http".equals(origin.getScheme())||!Set.of("127.0.0.1","[::1]","::1").contains(origin.getHost())||origin.getUserInfo()!=null||origin.getQuery()!=null||origin.getFragment()!=null||!(origin.getPath().isEmpty()||origin.getPath().equals("/")))throw new IllegalArgumentException("Explicit loopback metric output origin required");
        this.origin=origin;
    }
    private HttpResponse<String> request(String method,String path,String body) {
        try {
            var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(25));
            if(method.equals("POST"))request.header("Content-Type","text/plain; charset=utf-8").header("Stream-Mode","0").POST(HttpRequest.BodyPublishers.ofString(body));else request.GET();
            return client.send(request.build(),ignored->new VictoriaMetricsQueryAdapter.BoundedBody());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new OutputFailure(false);}
        catch(Exception unavailable){throw new OutputFailure(false);}
    }
    private void configuration() {
        var response=request("GET","/flags",null);if(response.statusCode()!=200)throw new OutputFailure(false);
        var flags=new HashMap<String,String>();for(var line:response.body().split("\n")){int equals=line.indexOf('=');if(equals>0)flags.put(line.substring(0,equals).replaceFirst("^-",""),line.substring(equals+1).replace("\"",""));}
        if(!"1ms".equals(flags.get("dedup.minScrapeInterval"))||!"false".equals(flags.getOrDefault("influx.forceStreamMode","false"))||!"false".equals(flags.getOrDefault("influxSkipSingleField","false"))||!"false".equals(flags.getOrDefault("influxSkipMeasurement","false"))||!"_".equals(flags.getOrDefault("influxMeasurementFieldSeparator","_"))||!"1ms".equals(flags.getOrDefault("influxTrimTimestamp","1ms")))throw new OutputFailure(false);
    }
    @Override public void write(MetricWriteBatch batch) {
        configuration();var times=batch.samples().stream().map(MetricWriteBatch.Sample::timestampMillis).toList();
        var present=new HashMap<Long,BigDecimal>();read(batch.labels(),times).forEach(point->present.put(point.timestampMillis(),point.value()));
        for(var point:batch.samples())if(present.containsKey(point.timestampMillis())&&present.get(point.timestampMillis()).compareTo(point.value())!=0)throw new OutputFailure(false);
        if(present.size()==batch.samples().size())return;
        var series=new StringBuilder("opsweave_workflow_metric");new TreeMap<>(batch.labels()).forEach((key,value)->series.append(',').append(key).append('=').append(value));
        var body=new StringBuilder();for(var point:batch.samples())if(!present.containsKey(point.timestampMillis()))body.append(series).append(" value=").append(point.value().toPlainString()).append(' ').append(point.timestampMillis()).append('\n');
        if(body.length()>4*1024*1024)throw new OutputFailure(false);
        try {
            if(request("POST","/write?precision=ms",body.toString()).statusCode()!=204)throw new OutputFailure(true);
            if(!confirmationDelay.isZero())try{Thread.sleep(confirmationDelay.toMillis());}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new OutputFailure(true);}
            var found=read(batch.labels(),times);
            if(found.size()!=batch.samples().size())throw new OutputFailure(true);
            for(int i=0;i<found.size();i++)if(found.get(i).timestampMillis()!=batch.samples().get(i).timestampMillis()||found.get(i).value().compareTo(batch.samples().get(i).value())!=0)throw new OutputFailure(true);
        }catch(RuntimeException unconfirmed){throw new OutputFailure(true);}
    }
    @Override public List<MetricWriteBatch.Sample> read(Map<String,String> labels,List<Long> timestamps) {
        if(timestamps.isEmpty()||timestamps.size()>com.acme.opsweave.integration.domain.WorkflowMetricStream.MAX_POINTS)throw new IllegalArgumentException();new MetricWriteBatch(labels,List.of(),0);
        var selector=new StringBuilder("opsweave_workflow_metric_value{");new TreeMap<>(labels).forEach((key,value)->selector.append(key).append("=\"").append(value).append("\","));selector.setLength(selector.length()-1);selector.append('}');
        var path="/api/v1/export?match%5B%5D="+URLEncoder.encode(selector.toString(),StandardCharsets.UTF_8)+"&start="+Collections.min(timestamps)/1000+"&end="+(Collections.max(timestamps)/1000+1)+"&reduce_mem_usage=1";
        var response=request("GET",path,null);if(response.statusCode()!=200)throw new OutputFailure(false);
        var expected=new HashMap<>(labels);expected.put("__name__","opsweave_workflow_metric_value");var selected=new HashSet<>(timestamps);var points=new TreeMap<Long,BigDecimal>();int scanned=0;
        try {
            for(var line:response.body().split("\n")) {
                if(line.isBlank())continue;var row=json.readTree(line);var actual=new HashMap<String,String>();
                if(!row.path("metric").isObject())throw new IllegalArgumentException();for(var entry:row.get("metric").properties()){if(!entry.getValue().isString())throw new IllegalArgumentException();actual.put(entry.getKey(),entry.getValue().asString());}
                if(!expected.equals(actual))throw new IllegalArgumentException();var times=row.get("timestamps");var values=row.get("values");
                if(times==null||values==null||!times.isArray()||!values.isArray()||times.size()!=values.size()||(scanned+=times.size())>8000)throw new IllegalArgumentException();
                for(int i=0;i<times.size();i++){if(!times.get(i).isIntegralNumber()||!times.get(i).canConvertToLong()||!values.get(i).isNumber())throw new IllegalArgumentException();long time=times.get(i).asLong();if(!selected.contains(time))continue;var value=new BigDecimal(values.get(i).asString());new MetricWriteBatch.Sample(time,value);var previous=points.putIfAbsent(time,value);if(previous!=null&&previous.compareTo(value)!=0)throw new IllegalArgumentException();}
            }
        }catch(RuntimeException malformed){throw new OutputFailure(false);}
        return points.entrySet().stream().map(entry->new MetricWriteBatch.Sample(entry.getKey(),entry.getValue())).toList();
    }
}
