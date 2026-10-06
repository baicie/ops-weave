package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.integration.application.WorkflowLogOutputService.OutputFailure;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Package-private transport shared by fixed log adapters. It has no caller-facing SQL endpoint. */
final class BoundedClickHouseLogTransport {
    private final URI origin;private final String user,key;private final int byteLimit;
    private final JsonMapper json=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector(){
        public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}
    }).build();
    BoundedClickHouseLogTransport(URI origin,String user,String key,int byteLimit){
        if(!"http".equals(origin.getScheme())||!Set.of("127.0.0.1","[::1]","::1").contains(origin.getHost())||origin.getUserInfo()!=null||origin.getQuery()!=null||origin.getFragment()!=null
            ||!(origin.getPath().isEmpty()||origin.getPath().equals("/"))||user==null||!user.matches("[a-zA-Z0-9_]{1,64}")||key==null||key.isBlank()||key.length()>256||key.chars().anyMatch(c->c<32||c==127)
            ||byteLimit<65536||byteLimit>8*1024*1024)throw new IllegalArgumentException("Explicit loopback log output configuration required");
        this.origin=origin;this.user=user;this.key=key;this.byteLimit=byteLimit;
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    String request(String sql,Map<String,String> parameters,String body,boolean attempted){
        if(body!=null&&body.getBytes(StandardCharsets.UTF_8).length>byteLimit)throw new OutputFailure(false);
        var path=new StringBuilder("/?query=").append(encode(sql));new TreeMap<>(parameters).forEach((name,value)->path.append("&param_").append(name).append('=').append(encode(value)));
        try{
            var builder=HttpRequest.newBuilder(origin.resolve(path.toString())).timeout(Duration.ofSeconds(8)).header("X-ClickHouse-User",user).header("X-ClickHouse-Key",key);
            if(body==null)builder.GET();else builder.header("Content-Type","application/x-ndjson; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body));
            var response=client.send(builder.build(),ignored->new BoundedBody(byteLimit));if(response.statusCode()!=200)throw new OutputFailure(attempted);return response.body();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new OutputFailure(attempted);}catch(Exception error){throw new OutputFailure(attempted);}
    }
    List<JsonNode> rows(String body,int limit){
        var rows=new ArrayList<JsonNode>();try{for(var line:body.split("\n")){if(line.isBlank())continue;var row=json.readTree(line);if(!row.isObject()||rows.size()>=limit)throw new IllegalArgumentException();rows.add(row);}return List.copyOf(rows);}
        catch(RuntimeException invalid){throw new OutputFailure(false);}
    }
    void configuration(String table,String columns,List<String> types,String sorting){
        var rows=rows(request("SELECT name,type FROM system.columns WHERE database='opsweave_logs' AND table='"+table+"' ORDER BY position SETTINGS max_execution_time=2,max_result_rows=16,result_overflow_mode='throw' FORMAT JSONEachRow",Map.of(),null,false),16);
        var names=columns.split(",");if(rows.size()!=names.length)throw new OutputFailure(false);
        for(int i=0;i<names.length;i++)if(!text(rows.get(i),"name").equals(names[i])||!text(rows.get(i),"type").equals(types.get(i)))throw new OutputFailure(false);
        var tables=rows(request("SELECT engine,sorting_key FROM system.tables WHERE database='opsweave_logs' AND name='"+table+"' SETTINGS max_execution_time=2,max_result_rows=1,result_overflow_mode='throw' FORMAT JSONEachRow",Map.of(),null,false),1);
        if(tables.size()!=1||!text(tables.getFirst(),"engine").equals("ReplacingMergeTree")||!text(tables.getFirst(),"sorting_key").replace(" ","").equals(sorting))throw new OutputFailure(false);
        request("SELECT row_index FROM opsweave_logs."+table+" LIMIT 0 SETTINGS max_execution_time=2 FORMAT JSONEachRow",Map.of(),null,false);
    }
    static String text(JsonNode row,String name){var v=row.get(name);if(v==null||!v.isString())throw new OutputFailure(false);return v.asString();}
    static String nullable(JsonNode row,String name){var v=row.get(name);if(v==null||!v.isNull()&&!v.isString())throw new OutputFailure(false);return v.isNull()?null:v.asString();}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<String>{
        private final HttpResponse.BodySubscriber<String> delegate=HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);private final int limit;private Flow.Subscription subscription;private long bytes;private boolean failed;
        BoundedBody(int limit){this.limit=limit;}public CompletionStage<String> getBody(){return delegate.getBody();}public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
        public void onNext(List<ByteBuffer> values){if(failed)return;for(var value:values)bytes+=value.remaining();if(bytes>limit){failed=true;subscription.cancel();delegate.onError(new java.io.IOException("LOG_RESPONSE_LIMIT"));}else delegate.onNext(values);}
        public void onError(Throwable failure){if(!failed)delegate.onError(failure);}public void onComplete(){if(!failed)delegate.onComplete();}
    }
}
