package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZabbixMetricPageReaderTest {
    static final URI URI_LOCAL=URI.create("http://127.0.0.1/api_jsonrpc.php");
    static final Instant AT=Instant.parse("2026-10-03T12:00:00Z");
    static final class Transport implements ZabbixJsonRpcConnector.Transport {
        List<String> ids=java.util.stream.IntStream.rangeClosed(1,45).mapToObj(Integer::toString).toList();
        int calls,changeAt;boolean missing,extra,reverse;Long count;String params,method,hostGroupId="91";List<String> requestBodies=new ArrayList<>();
        public String exchange(URI endpoint,String body,String token){
            assertEquals(URI_LOCAL,endpoint);assertEquals("fixture-token",token);var n=CatalogJson.JSON.readTree(body);method=n.get("method").asString();
            if(method.equals("item.get")){assertFalse(n.get("params").get("templated").booleanValue());assertFalse(n.get("params").has("offset"));assertFalse(n.get("params").has("selectHosts"));}
            else {assertEquals("host.get",method);assertTrue(n.get("params").has("selectGroups"));}
            params=CatalogJson.JSON.writeValueAsString(n.get("params"));requestBodies.add(body);calls++;return "fixture";
        }
        public long readCount(String json){assertTrue(CatalogJson.JSON.readTree(params).get("countOutput").booleanValue());return count==null?ids.size():count;}
        public List<Map<String,Object>> readHostArray(String json){
            var p=CatalogJson.JSON.readTree(params);if(method.equals("host.get"))return List.of(Map.of("hostid","101","groups",List.of(Map.of("groupid",hostGroupId))));var observed=changeAt>0&&calls>=changeAt?ids.stream().map(v->String.valueOf(Long.parseLong(v)+1)).toList():ids;
            if(p.get("output").size()==1){assertEquals(1001,p.get("limit").asInt());var rows=observed.stream().map(id->Map.<String,Object>of("itemid",id)).toList();return reverse?rows.reversed():rows;}
            assertEquals(6,p.get("output").size());assertTrue(p.get("itemids").size()<=20);assertEquals(p.get("itemids").size(),p.get("limit").asInt());var rows=new ArrayList<Map<String,Object>>();
            for(var id:p.get("itemids")){assertTrue(observed.contains(id.asString()));rows.add(ZabbixMetricMetadataReaderTest.item(id.asString(),"fixture.cpu","0"));}
            if(missing&&!rows.isEmpty())rows.removeLast();if(extra)rows.add(ZabbixMetricMetadataReaderTest.item("999","fixture.extra","0"));return rows;
        }
        public String readText(String json){throw new AssertionError("No values, version probe or arbitrary methods");}
    }
    SourceMetricPage read(Transport t,SourceMetricPage previous){return new ZabbixMetricPageReader(new MappingRegistry(List.of(ZabbixMetricMetadataReaderTest.mapping()))).read(URI_LOCAL,t,"fixture-token",UUID.randomUUID(),AT,previous);}
    SourceMetricPage read(Transport t,SourceMetricPage previous,List<String> groups){return new ZabbixMetricPageReader(new MappingRegistry(List.of(ZabbixMetricMetadataReaderTest.mapping()))).read(URI_LOCAL,t,"fixture-token",UUID.randomUUID(),AT,previous,groups);}
    @Test void fortyFiveItemsCoverThreeFixedPagesWithOnlyFiveReadsEach(){var t=new Transport();var a=read(t,null);assertEquals(5,t.calls);assertEquals(20,a.items().size());assertFalse(a.complete());assertEquals(20,a.nextOffset());var b=read(t,a);assertEquals(10,t.calls);assertEquals(20,b.offset());assertEquals(a.manifest(),b.manifest());var c=read(t,b);assertEquals(15,t.calls);assertEquals(5,c.items().size());assertTrue(c.complete());assertNull(c.nextOffset());assertFalse(c.toString().contains("private-fixture"));}
    @Test void changeBeforeContinuationStopsBeforeMetadata(){var t=new Transport();var a=read(t,null);t.changeAt=6;var b=read(t,a);assertEquals(7,t.calls);assertEquals("MEMBERSHIP_CHANGED",b.statusCode());assertTrue(b.items().isEmpty());assertFalse(b.complete());assertNull(b.nextOffset());}
    @Test void changeAfterMetadataDiscardsThePage(){var t=new Transport();t.changeAt=5;var a=read(t,null);assertEquals(5,t.calls);assertEquals("MEMBERSHIP_CHANGED",a.statusCode());assertTrue(a.items().isEmpty());}
    @Test void capacityDoesNotReadOrPretendEmptySuccess(){var t=new Transport();t.count=1001L;var a=read(t,null);assertEquals(1,t.calls);assertEquals("CAPACITY",a.statusCode());assertNull(a.manifest());assertFalse(a.complete());}
    @Test void emptyAuthorizedMembershipIsCompleteWithoutMetadataRequest(){var t=new Transport();t.ids=List.of();var a=read(t,null);assertEquals(4,t.calls);assertTrue(a.complete());assertEquals(0,a.manifest().total());}
    @Test void missingExtraReorderedOrMismatchedCountFailsClosed(){for(int i=0;i<4;i++){var t=new Transport();t.missing=i==0;t.extra=i==1;t.reverse=i==2;t.count=i==3?44L:null;assertThrows(RuntimeException.class,()->read(t,null));assertTrue(t.calls<=3);}}
    @Test void idsBeyondLongAreStoredExactly(){var t=new Transport();t.ids=List.of("9223372036854775808","99999999999999999999");var a=read(t,null);assertEquals(t.ids,a.manifest().itemIds());assertTrue(a.complete());}
    @Test void scopedPageChecksHostGroupsWithinItsBoundedReadSet(){var t=new Transport();t.ids=List.of("1");var page=read(t,null,List.of("91"));assertEquals(6,t.calls);assertTrue(page.complete());assertTrue(t.requestBodies.stream().allMatch(body->CatalogJson.JSON.readTree(body).get("params").has("groupids")));t=new Transport();t.ids=List.of("1");t.hostGroupId="92";var outOfScope=t;assertThrows(IllegalStateException.class,()->read(outOfScope,null,List.of("91")));assertEquals(4,outOfScope.calls);}
    @Test void privateCodecSeparatesMembershipAndRejectsCorruption(){var t=new Transport();var request=UUID.randomUUID();var page=new ZabbixMetricPageReader(new MappingRegistry(List.of())).read(URI_LOCAL,t,"fixture-token",request,AT,null);String pin="sha256:"+"a".repeat(64);var source=UUID.randomUUID();var receipt=new SourceInspection(request,source,"DISCOVER_METRIC_PAGE",1,pin,"zabbix-jsonrpc",SourceInspection.commandDigest(source,request,"DISCOVER_METRIC_PAGE",1,pin,null),"COMPLETED",AT,AT.plusSeconds(65),AT,AT.plusSeconds(900),null,null,null,null,page);
        var storage=SourceInspectionJson.storage(receipt);assertEquals(receipt,SourceInspectionJson.decode(CatalogJson.JSON.writeValueAsString(storage)));var wire=SourceInspectionJson.wire(receipt);assertFalse(wire.containsKey("metricMembership"));assertFalse(CatalogJson.JSON.writeValueAsString(wire).contains("itemIds"));storage.put("metricMembership",List.of("1"));assertThrows(IllegalArgumentException.class,()->SourceInspectionJson.decode(CatalogJson.JSON.writeValueAsString(storage)));
    }
}
