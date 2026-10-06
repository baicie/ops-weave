package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.application.WorkflowService;
import com.acme.opsweave.integration.domain.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** One fixed item, up to 20 history pages, and pinned metadata/membership checks (24 RPCs maximum). */
public final class ZabbixWorkflowMetricReader {
    public static final int WINDOW_SECONDS=600, SAMPLE_LIMIT=5;
    public static final int PAGE_LIMIT=101, READ_BUDGET_SECONDS=20;
    private final LongSupplier monotonic;
    public ZabbixWorkflowMetricReader(){this(System::nanoTime);}
    public ZabbixWorkflowMetricReader(LongSupplier monotonic){this.monotonic=Objects.requireNonNull(monotonic);}
    public WorkflowService.Batch read(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowMetricSourcePin pin,Instant now) {
        return read(endpoint,transport,token,pin,now,List.of());
    }
    public WorkflowService.Batch read(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowMetricSourcePin pin,Instant now,List<String> hostGroupIds) {
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);
        try {
            metadata(endpoint,transport,token,pin,groups);
            long till=now.getEpochSecond(),from=Math.max(0,till-WINDOW_SECONDS);
            String type=pin.sourceValueType().equals("FLOAT")?"0":"3";
            var rows=call(endpoint,transport,token,"history.get","\"output\":[\"itemid\",\"clock\",\"ns\",\"value\"],\"history\":"+type
                +",\"itemids\":[\""+pin.itemId()+"\"],\"time_from\":"+from+",\"time_till\":"+till
                +",\"sortfield\":[\"clock\",\"ns\"],\"sortorder\":[\"DESC\",\"DESC\"],\"limit\":6");
            if(rows.isEmpty()||rows.size()>6)throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
            var records=new ArrayList<Map<String,Object>>();HistoryCursor previous=null;
            for(var row:rows) {
                if(!pin.itemId().equals(text(row,"itemid")))throw new IllegalArgumentException();
                var position=new HistoryCursor(Long.parseLong(text(row,"clock")),Integer.parseInt(text(row,"ns")));
                if(position.clock()<from||position.clock()>till||position.instant().isAfter(now)||previous!=null&&position.compareTo(previous)>=0)throw new IllegalArgumentException();
                previous=position;String value=text(row,"value");
                if(value.length()>64||value.isBlank())throw new IllegalArgumentException();
                var number=new BigDecimal(value);
                if(number.precision()>64||Math.abs((long)number.scale())>308||!Double.isFinite(number.doubleValue())
                    ||type.equals("3")&&(!value.matches("[0-9]{1,20}")||number.compareTo(new BigDecimal("18446744073709551615"))>0))throw new IllegalArgumentException();
                if(records.size()<SAMPLE_LIMIT)records.add(Map.of("timestamp",position.instant().toString(),"sourceKey",pin.sourceKey(),"value",value));
            }
            metadata(endpoint,transport,token,pin,groups);
            Collections.reverse(records);
            return new WorkflowService.Batch(records,"zabbix-jsonrpc",rows.size(),0,rows.size()>SAMPLE_LIMIT,"SUCCEEDED");
        } catch(WorkflowFailure known){throw known;}
        catch(IllegalArgumentException malformed){throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);}
        catch(RuntimeException unavailable){throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
    }
    /** Replays the entire sentinel second; never skips nanosecond positions using clock+1. */
    public List<Map<String,Object>> readWindow(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowMetricSourcePin pin,Instant from,Instant till,Instant now) {
        return readWindow(endpoint,transport,token,pin,from,till,now,WorkflowMetricStream.MAX_POINTS,List.of());
    }
    /** A caller may narrow the existing point budget; it cannot enlarge it. */
    public List<Map<String,Object>> readWindow(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowMetricSourcePin pin,Instant from,Instant till,Instant now,int maxPoints) {
        return readWindow(endpoint,transport,token,pin,from,till,now,maxPoints,List.of());
    }
    public List<Map<String,Object>> readWindow(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowMetricSourcePin pin,Instant from,Instant till,Instant now,int maxPoints,List<String> hostGroupIds) {
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);
        if(maxPoints<1||maxPoints>WorkflowMetricStream.MAX_POINTS)throw new IllegalArgumentException();
        if(from.getNano()!=0||from.getEpochSecond()<0||!till.equals(from.plusSeconds(WorkflowMetricStream.WINDOW_SECONDS))||now.isBefore(till.plusSeconds(WorkflowMetricStream.SETTLE_SECONDS))||from.isBefore(now.minusSeconds(86400)))throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
        try {
            long started=monotonic.getAsLong();
            var bounded=new ZabbixJsonRpcConnector.Transport(){
                public String exchange(URI uri,String body,String secret){
                    long left=Duration.ofSeconds(READ_BUDGET_SECONDS).toNanos()-(monotonic.getAsLong()-started);
                    if(left<=0)throw new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);
                    String result=transport.exchangeWithin(uri,body,secret,Duration.ofNanos(left));
                    if(monotonic.getAsLong()-started>=Duration.ofSeconds(READ_BUDGET_SECONDS).toNanos())throw new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);
                    return result;
                }
                public List<Map<String,Object>> readHostArray(String body){return transport.readHostArray(body);}
            };
            metadata(endpoint,bounded,token,pin,groups);String type=pin.sourceValueType().equals("FLOAT")?"0":"3";
            var records=new ArrayList<Map<String,Object>>();long next=from.getEpochSecond(),end=till.getEpochSecond()-1;int requests=0;
            while(next<=end){
                if(++requests>WorkflowMetricStream.MAX_HISTORY_REQUESTS)throw new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);
                int pageLimit=Math.min(PAGE_LIMIT,maxPoints-records.size()+1);
                var rows=windowPage(endpoint,bounded,token,pin,type,next,end,pageLimit);
                long through=end;
                if(rows.size()==pageLimit){
                    long boundary=Instant.parse((String)rows.getLast().get("timestamp")).getEpochSecond();
                    if(boundary==next){
                        if(++requests>WorkflowMetricStream.MAX_HISTORY_REQUESTS)throw new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);
                        int remaining=maxPoints-records.size();
                        rows=windowPage(endpoint,bounded,token,pin,type,next,next,remaining+1);
                        if(rows.size()>remaining)throw new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);
                        through=next;
                    }else{
                        through=boundary-1;long safe=through;
                        rows=rows.stream().filter(row->Instant.parse((String)row.get("timestamp")).getEpochSecond()<=safe).toList();
                    }
                }
                if(records.size()+rows.size()>maxPoints)throw new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);
                records.addAll(rows);next=through+1;
            }
            metadata(endpoint,bounded,token,pin,groups);return List.copyOf(records);
        }catch(WorkflowFailure known){throw known;}catch(IllegalArgumentException malformed){throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);}catch(RuntimeException unavailable){throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
    }
    private List<Map<String,Object>> windowPage(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowMetricSourcePin pin,String type,long from,long till,int limit){
        var rows=call(endpoint,transport,token,"history.get","\"output\":[\"itemid\",\"clock\",\"ns\",\"value\"],\"history\":"+type
            +",\"itemids\":[\""+pin.itemId()+"\"],\"time_from\":"+from+",\"time_till\":"+till+",\"sortfield\":[\"clock\",\"ns\"],\"sortorder\":[\"ASC\",\"ASC\"],\"limit\":"+limit);
        if(rows.size()>limit)throw new IllegalArgumentException();
        var records=new ArrayList<Map<String,Object>>();HistoryCursor previous=null;
        for(var row:rows){
            if(!pin.itemId().equals(text(row,"itemid")))throw new IllegalArgumentException();var position=new HistoryCursor(Long.parseLong(text(row,"clock")),Integer.parseInt(text(row,"ns")));
            if(position.clock()<from||position.clock()>till||previous!=null&&position.compareTo(previous)<=0)throw new IllegalArgumentException();previous=position;
            String value=text(row,"value");if(value.length()>64||value.isBlank())throw new IllegalArgumentException();var number=new BigDecimal(value);
            if(number.precision()>64||Math.abs((long)number.scale())>308||!Double.isFinite(number.doubleValue())||type.equals("3")&&(!value.matches("[0-9]{1,20}")||number.compareTo(new BigDecimal("18446744073709551615"))>0))throw new IllegalArgumentException();
            records.add(Map.of("timestamp",position.instant().toString(),"sourceKey",pin.sourceKey(),"value",value));
        }
        return List.copyOf(records);
    }
    private void metadata(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowMetricSourcePin pin,List<String> hostGroupIds) {
        var rows=call(endpoint,transport,token,"item.get","\"output\":[\"itemid\",\"hostid\",\"key_\",\"units\",\"value_type\"],\"itemids\":[\""+pin.itemId()+"\"],\"limit\":2"+ZabbixJsonRpcConnector.groupIdsParameter(hostGroupIds));
        if(rows.size()!=1)throw WorkflowFailure.sourceChanged(WorkflowDiagnostics.SourceFailure.METADATA_CHANGED);
        var i=rows.getFirst();String type=pin.sourceValueType().equals("FLOAT")?"0":"3";
        if(!pin.itemId().equals(text(i,"itemid"))||!pin.hostId().equals(text(i,"hostid")))throw WorkflowFailure.sourceChanged(WorkflowDiagnostics.SourceFailure.METADATA_CHANGED);
        if(!pin.sourceKey().equals(text(i,"key_")))throw WorkflowFailure.sourceChanged(WorkflowDiagnostics.SourceFailure.SOURCE_KEY_CHANGED);
        if(!type.equals(text(i,"value_type")))throw WorkflowFailure.sourceChanged(WorkflowDiagnostics.SourceFailure.VALUE_TYPE_CHANGED);
        if(!pin.sourceUnit().equals(text(i,"units")))throw WorkflowFailure.sourceChanged(WorkflowDiagnostics.SourceFailure.UNIT_CHANGED);
        if(!hostGroupIds.isEmpty())ZabbixHostGroupVerifier.requireMembership(endpoint,transport,token,List.of(pin.hostId()),hostGroupIds);
    }
    private static List<Map<String,Object>> call(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,String method,String params) {
        return transport.readHostArray(transport.exchange(endpoint,"{\"jsonrpc\":\"2.0\",\"method\":\""+method+"\",\"params\":{"+params+"},\"id\":1}",token));
    }
    private static String text(Map<String,Object> row,String key){if(!(row.get(key) instanceof String value))throw new IllegalArgumentException();return value;}
}
