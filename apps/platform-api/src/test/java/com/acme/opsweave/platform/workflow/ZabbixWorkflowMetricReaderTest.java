package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol fixture: tests exact requests and untrusted responses, never a real source claim. */
class ZabbixWorkflowMetricReaderTest {
 static final Instant NOW=Instant.parse("2026-10-04T00:00:00Z");
 static final UUID UUID_FIXED=UUID.fromString("10000000-0000-4000-8000-000000000001");
 static WorkflowMetricSourcePin source(String type){return new WorkflowMetricSourcePin(UUID_FIXED,"1","101","system.cpu.util[,user]","%",type,WorkflowMetricSourcePin.digest(UUID_FIXED,"1","101","system.cpu.util[,user]","%",type));}
 static Map<String,Object> item(){return Map.of("itemid","1","hostid","101","key_","system.cpu.util[,user]","units","%","value_type","0","lastvalue","private-fixture","Authorization","private-fixture");}
 static Map<String,Object> value(int seconds,String raw){return Map.of("itemid","1","clock",""+(NOW.getEpochSecond()-seconds),"ns","0","value",raw);}
 static class Transport implements ZabbixJsonRpcConnector.Transport {
  List<Map<String,Object>> first=List.of(item()),last=first,history=List.of(value(1,"12.5"));int calls;String method;boolean fail;
  public String exchange(URI uri,String body,String token){assertEquals("fixture-token",token);if(fail)throw new IllegalStateException("private-fixture");var n=CatalogJson.JSON.readTree(body);method=n.get("method").asString();var p=n.get("params");assertEquals("1",p.get("itemids").get(0).asString());assertEquals(1,p.get("itemids").size());
   if(method.equals("history.get")){assertEquals(6,p.get("limit").asInt());assertEquals(NOW.getEpochSecond()-600,p.get("time_from").asLong());assertEquals(NOW.getEpochSecond(),p.get("time_till").asLong());assertEquals("DESC",p.get("sortorder").get(0).asString());assertEquals(4,p.get("output").size());assertFalse(p.has("filter"));}else{assertEquals("item.get",method);assertEquals(2,p.get("limit").asInt());assertEquals(5,p.get("output").size());}calls++;return "fixture";}
  public List<Map<String,Object>> readHostArray(String ignored){return method.equals("history.get")?history:calls==1?first:last;}
  public String readText(String ignored){throw new AssertionError();}
 }
 static final class ScopedTransport extends Transport {
  String hostGroupId="91";
  @Override public String exchange(URI uri,String body,String token){var request=CatalogJson.JSON.readTree(body);if(request.get("method").asString().equals("host.get")){var p=request.get("params");assertEquals("101",p.get("hostids").get(0).asString());assertEquals("91",p.get("groupids").get(0).asString());assertTrue(p.has("selectGroups"));calls++;return "groups";}return super.exchange(uri,body,token);}
  @Override public List<Map<String,Object>> readHostArray(String body){return body.equals("groups")?List.of(Map.of("hostid","101","groups",List.of(Map.of("groupid",hostGroupId)))):super.readHostArray(body);}
 }
 static com.acme.opsweave.integration.application.WorkflowService.Batch read(Transport t){return new ZabbixWorkflowMetricReader().read(URI.create("http://127.0.0.1/api_jsonrpc.php"),t,"fixture-token",source("FLOAT"),NOW);}
 static void failure(WorkflowFailure.Code code,Transport t){assertEquals(code,assertThrows(WorkflowFailure.class,()->read(t)).code());}
 @Test void rawPercentIsNotNormalizedAndSentinelRemainsPartial(){var t=new Transport();t.history=java.util.stream.IntStream.rangeClosed(1,6).mapToObj(i->value(i,"12.5")).toList();var b=read(t);assertEquals(3,t.calls);assertEquals(5,b.records().size());assertEquals(6,b.retainedCount());assertTrue(b.truncated());assertEquals("12.5",b.records().getFirst().get("value"));assertEquals(NOW.minusSeconds(5).toString(),b.records().getFirst().get("timestamp"));assertEquals(Set.of("timestamp","sourceKey","value"),b.records().getFirst().keySet());assertFalse(b.toString().contains("private-fixture"));}
 @Test void bothMetadataChecksRejectAnySeriesSemanticChange(){for(String key:List.of("itemid","hostid","key_","units","value_type")){var changed=new HashMap<>(item());changed.put(key,key.equals("value_type")?"3":"changed");var t=new Transport();t.first=List.of(changed);failure(WorkflowFailure.Code.SOURCE_CHANGED,t);assertEquals(1,t.calls);t=new Transport();t.last=List.of(changed);failure(WorkflowFailure.Code.SOURCE_CHANGED,t);assertEquals(3,t.calls);}}
 @Test void missingAndMultipleItemsDoNotBecomeEmptySuccess(){for(var rows:List.of(List.<Map<String,Object>>of(),List.of(item(),item()))){var t=new Transport();t.first=rows;failure(WorkflowFailure.Code.SOURCE_CHANGED,t);assertEquals(1,t.calls);}}
 @Test void rawReadEnforcesCountItemPositionOrderAndNumericBudget(){var cases=new ArrayList<List<Map<String,Object>>>();cases.add(List.of());cases.add(Collections.nCopies(7,value(1,"1")));cases.add(List.of(value(1,"1"),value(1,"1")));cases.add(List.of(value(2,"1"),value(1,"1")));cases.add(List.of(value(601,"1")));cases.add(List.of(value(-1,"1")));for(String raw:List.of("NaN","1e99999999","1".repeat(65)," "))cases.add(List.of(value(1,raw)));var foreign=new HashMap<>(value(1,"1"));foreign.put("itemid","2");cases.add(List.of(foreign));for(var rows:cases){var t=new Transport();t.history=rows;failure(WorkflowFailure.Code.INVALID_SAMPLE,t);assertEquals(2,t.calls);}}
 @Test void unsignedValuesAreBoundedAndNeverSilentlyConverted(){var t=new Transport();var meta=new HashMap<>(item());meta.put("value_type","3");t.first=t.last=List.of(meta);t.history=List.of(value(1,"18446744073709551615"));var b=new ZabbixWorkflowMetricReader().read(URI.create("http://127.0.0.1/api_jsonrpc.php"),t,"fixture-token",source("UNSIGNED"),NOW);assertEquals("18446744073709551615",b.records().getFirst().get("value"));for(String raw:List.of("18446744073709551616","-1","1.5","1e2")){t.history=List.of(value(1,raw));var test=t;assertEquals(WorkflowFailure.Code.INVALID_SAMPLE,assertThrows(WorkflowFailure.class,()->new ZabbixWorkflowMetricReader().read(URI.create("http://127.0.0.1/api_jsonrpc.php"),test,"fixture-token",source("UNSIGNED"),NOW)).code());}}
 @Test void transportFailureHasStableCodeAndNoVendorText(){var t=new Transport();t.fail=true;var e=assertThrows(WorkflowFailure.class,()->read(t));assertEquals(WorkflowFailure.Code.SOURCE_UNAVAILABLE,e.code());assertEquals("SOURCE_UNAVAILABLE",e.getMessage());assertNull(e.getCause());}
 @Test void configuredGroupIsCheckedBeforeAndAfterHistoryRead(){var t=new ScopedTransport();var r=new ZabbixWorkflowMetricReader().read(URI.create("http://127.0.0.1/api_jsonrpc.php"),t,"fixture-token",source("FLOAT"),NOW,List.of("91"));assertEquals(5,t.calls);assertEquals(1,r.records().size());t=new ScopedTransport();t.hostGroupId="92";var outside=t;assertEquals(WorkflowFailure.Code.SOURCE_UNAVAILABLE,assertThrows(WorkflowFailure.class,()->new ZabbixWorkflowMetricReader().read(URI.create("http://127.0.0.1/api_jsonrpc.php"),outside,"fixture-token",source("FLOAT"),NOW,List.of("91"))).code());assertEquals(2,outside.calls);}
}
