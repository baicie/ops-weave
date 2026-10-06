package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.application.WorkflowService;
import com.acme.opsweave.integration.domain.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Fixed LOG samples or bounded windows: at most 20 history pages plus four pinned metadata/membership RPCs. */
public final class ZabbixWorkflowLogReader {
    public static final int WINDOW_SECONDS=600,SAMPLE_LIMIT=5,READ_BUDGET_SECONDS=20;
    private final LongSupplier monotonic;
    public ZabbixWorkflowLogReader(){this(System::nanoTime);}
    public ZabbixWorkflowLogReader(LongSupplier monotonic){this.monotonic=Objects.requireNonNull(monotonic);}
    /** Full sentinel-second replay avoids dropping positions that share the boundary clock. */
    public List<Map<String,Object>> readWindow(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowLogSourcePin pin,Instant from,Instant till,Instant now){return readWindow(endpoint,transport,token,pin,from,till,now,List.of());}
    public List<Map<String,Object>> readWindow(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowLogSourcePin pin,Instant from,Instant till,Instant now,List<String> hostGroupIds){
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);
        WorkflowLogWindow.requireWindow(from,till,now);
        try{
            var bounded=bounded(transport,WorkflowFailure.Code.WINDOW_INCOMPLETE);
            metadata(endpoint,bounded,token,pin,groups);
            var records=new ArrayList<Map<String,Object>>();long next=from.getEpochSecond(),end=till.getEpochSecond()-1;int requests=0;
            while(next<=end){
                if(++requests>WorkflowLogWindow.MAX_HISTORY_REQUESTS)throw new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);
                var page=windowPage(endpoint,bounded,token,pin,next,end,101,now);long through=end;
                if(page.size()==101){
                    long boundary=Instant.parse((String)page.getLast().get("timestamp")).getEpochSecond();
                    if(boundary==next){
                        if(++requests>WorkflowLogWindow.MAX_HISTORY_REQUESTS)throw new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);
                        int remaining=WorkflowLogWindow.MAX_RECORDS-records.size();
                        page=windowPage(endpoint,bounded,token,pin,next,next,remaining+1,now);
                        if(page.size()>remaining)throw new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);
                        through=next;
                    }else{
                        through=boundary-1;long safe=through;
                        page=page.stream().filter(row->Instant.parse((String)row.get("timestamp")).getEpochSecond()<=safe).toList();
                    }
                }
                if(records.size()+page.size()>WorkflowLogWindow.MAX_RECORDS)throw new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);
                records.addAll(page);next=through+1;
            }
            metadata(endpoint,bounded,token,pin,groups);return List.copyOf(records);
        }catch(WorkflowFailure known){throw known;}catch(IllegalArgumentException|DateTimeException malformed){throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);}catch(RuntimeException unavailable){throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
    }
    private ZabbixJsonRpcConnector.Transport bounded(ZabbixJsonRpcConnector.Transport transport,WorkflowFailure.Code exhausted){
        long started=monotonic.getAsLong();
        return new ZabbixJsonRpcConnector.Transport(){
            public String exchange(URI uri,String body,String secret){
                long left=Duration.ofSeconds(READ_BUDGET_SECONDS).toNanos()-(monotonic.getAsLong()-started);
                if(left<=0)throw new WorkflowFailure(exhausted);
                var result=transport.exchangeWithin(uri,body,secret,Duration.ofNanos(left));
                if(monotonic.getAsLong()-started>=Duration.ofSeconds(READ_BUDGET_SECONDS).toNanos())throw new WorkflowFailure(exhausted);
                return result;
            }
            public List<Map<String,Object>> readHostArray(String body){var rows=transport.readHostArray(body);if(monotonic.getAsLong()-started>=Duration.ofSeconds(READ_BUDGET_SECONDS).toNanos())throw new WorkflowFailure(exhausted);return rows;}
        };
    }
    private List<Map<String,Object>> windowPage(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowLogSourcePin pin,long from,long till,int limit,Instant now){
        var rows=call(endpoint,transport,token,"history.get","\"output\":[\"itemid\",\"clock\",\"ns\",\"value\",\"timestamp\",\"severity\",\"source\",\"logeventid\"],\"history\":2,\"itemids\":[\""+pin.itemId()+"\"],\"time_from\":"+from+",\"time_till\":"+till+",\"sortfield\":[\"clock\",\"ns\"],\"sortorder\":[\"ASC\",\"ASC\"],\"limit\":"+limit);
        if(rows.size()>limit)throw new IllegalArgumentException();var result=new ArrayList<Map<String,Object>>();HistoryCursor previous=null;
        for(var row:rows){var position=position(row,pin,from,till,now);if(previous!=null&&position.compareTo(previous)<=0)throw new IllegalArgumentException();previous=position;result.add(record(row,pin,position,now));}
        return List.copyOf(result);
    }
    public WorkflowService.Batch read(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowLogSourcePin pin,Instant now){
        return read(endpoint,transport,token,pin,now,List.of());
    }
    public WorkflowService.Batch read(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowLogSourcePin pin,Instant now,List<String> hostGroupIds){
        var groups=SourceConnectionConfiguration.normalizeHostGroupIds(hostGroupIds);
        try{
            var bounded=bounded(transport,WorkflowFailure.Code.SOURCE_UNAVAILABLE);
            metadata(endpoint,bounded,token,pin,groups);
            long till=now.getEpochSecond(),from=Math.max(0,till-WINDOW_SECONDS);
            var rows=call(endpoint,bounded,token,"history.get","\"output\":[\"itemid\",\"clock\",\"ns\",\"value\",\"timestamp\",\"severity\",\"source\",\"logeventid\"],\"history\":2,\"itemids\":[\""+pin.itemId()+"\"],\"time_from\":"+from+",\"time_till\":"+till+",\"sortfield\":[\"clock\",\"ns\"],\"sortorder\":[\"DESC\",\"DESC\"],\"limit\":6");
            if(rows.isEmpty()||rows.size()>6)throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);
            var records=new ArrayList<Map<String,Object>>();HistoryCursor previous=null;
            for(var row:rows){
                var position=position(row,pin,from,till,now);
                if(previous!=null&&position.compareTo(previous)>=0)throw new IllegalArgumentException();previous=position;
                var record=record(row,pin,position,now);if(records.size()<SAMPLE_LIMIT)records.add(record);
            }
            metadata(endpoint,bounded,token,pin,groups);Collections.reverse(records);
            return new WorkflowService.Batch(records,"zabbix-jsonrpc",rows.size(),0,rows.size()>SAMPLE_LIMIT,"SUCCEEDED");
        }catch(WorkflowFailure known){throw known;}catch(IllegalArgumentException|DateTimeException malformed){throw new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);}catch(RuntimeException unavailable){throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
    }
    private static HistoryCursor position(Map<String,Object> row,WorkflowLogSourcePin pin,long from,long till,Instant now){
        if(!pin.itemId().equals(text(row,"itemid")))throw new IllegalArgumentException();
        var position=new HistoryCursor(integer(row,"clock",Long.MAX_VALUE),Math.toIntExact(integer(row,"ns",999999999)));
        if(position.clock()<from||position.clock()>till||position.instant().isAfter(now))throw new IllegalArgumentException();return position;
    }
    private static Map<String,Object> record(Map<String,Object> row,WorkflowLogSourcePin pin,HistoryCursor position,Instant now){
        String body=text(row,"value");if(body.isBlank()||body.length()>2048)throw new IllegalArgumentException();
        long event=integer(row,"timestamp",Long.MAX_VALUE),severity=integer(row,"severity",Integer.MAX_VALUE),id=integer(row,"logeventid",4294967295L);
        var eventTime=event==0?null:Instant.ofEpochSecond(event);if(eventTime!=null&&eventTime.isAfter(now))throw new IllegalArgumentException();
        String eventSource=text(row,"source");if(eventSource.length()>128||eventSource.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
        var record=new LinkedHashMap<String,Object>();record.put("timestamp",position.instant().toString());record.put("body",body);record.put("sourceKey",pin.sourceKey());
        record.put("logEventTime",eventTime==null?null:eventTime.toString());record.put("severityCode",Long.toString(severity));record.put("eventSource",eventSource.isEmpty()?null:eventSource);record.put("eventId",Long.toString(id));return Collections.unmodifiableMap(record);
    }
    private static long integer(Map<String,Object> row,String key,long maximum){String value=text(row,key);if(!value.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();long result=Long.parseLong(value);if(result>maximum)throw new IllegalArgumentException();return result;}
    private static void metadata(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,WorkflowLogSourcePin pin,List<String> hostGroupIds){
        var rows=call(endpoint,transport,token,"item.get","\"output\":[\"itemid\",\"hostid\",\"key_\",\"units\",\"value_type\"],\"itemids\":[\""+pin.itemId()+"\"],\"limit\":2"+ZabbixJsonRpcConnector.groupIdsParameter(hostGroupIds));
        if(rows.size()!=1)throw WorkflowFailure.sourceChanged(WorkflowDiagnostics.SourceFailure.METADATA_CHANGED);var item=rows.getFirst();
        if(!pin.itemId().equals(text(item,"itemid"))||!pin.hostId().equals(text(item,"hostid")))throw WorkflowFailure.sourceChanged(WorkflowDiagnostics.SourceFailure.METADATA_CHANGED);
        if(!pin.sourceKey().equals(text(item,"key_")))throw WorkflowFailure.sourceChanged(WorkflowDiagnostics.SourceFailure.SOURCE_KEY_CHANGED);
        if(!"2".equals(text(item,"value_type")))throw WorkflowFailure.sourceChanged(WorkflowDiagnostics.SourceFailure.VALUE_TYPE_CHANGED);
        if(!pin.sourceUnit().equals(text(item,"units")))throw WorkflowFailure.sourceChanged(WorkflowDiagnostics.SourceFailure.UNIT_CHANGED);
        if(!hostGroupIds.isEmpty())ZabbixHostGroupVerifier.requireMembership(endpoint,transport,token,List.of(pin.hostId()),hostGroupIds);
    }
    private static List<Map<String,Object>> call(URI endpoint,ZabbixJsonRpcConnector.Transport transport,String token,String method,String params){return transport.readHostArray(transport.exchange(endpoint,"{\"jsonrpc\":\"2.0\",\"method\":\""+method+"\",\"params\":{"+params+"},\"id\":1}",token));}
    private static String text(Map<String,Object> row,String key){if(!(row.get(key) instanceof String value))throw new IllegalArgumentException();return value;}
}
