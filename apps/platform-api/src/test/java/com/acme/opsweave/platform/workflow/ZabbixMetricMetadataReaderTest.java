package com.acme.opsweave.platform.workflow;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.telemetry.domain.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZabbixMetricMetadataReaderTest {
    static Map<String,Object> item(String id,String key,String type){return Map.of("itemid",id,"hostid","101","key_",key,"name","Fixture "+id,"units","%","value_type",type,"lastvalue","private-fixture-value","password","private-fixture-password");}
    static final class Transport implements ZabbixJsonRpcConnector.Transport {
        List<Map<String,Object>> first=List.of(item("1","fixture.cpu","0")),second=first;String hostGroupId="91";int calls;List<String> requests=new ArrayList<>();
        public String exchange(URI uri,String body,String token){assertEquals("fixture-token",token);var n=CatalogJson.JSON.readTree(body);requests.add(body);calls++;if(n.get("method").asString().equals("host.get")){assertTrue(n.get("params").get("selectGroups").isArray());assertTrue(n.get("params").get("groupids").isArray());return "host";}assertEquals("item.get",n.get("method").asString());assertEquals(21,n.get("params").get("limit").asInt());assertFalse(n.get("params").get("templated").booleanValue());assertEquals(6,n.get("params").get("output").size());if(!n.get("params").has("groupids"))assertEquals(ZabbixMetricMetadataReader.REQUEST,body);return "fixture";}
        public List<Map<String,Object>> readHostArray(String json){if(json.equals("host"))return List.of(Map.of("hostid","101","groups",List.of(Map.of("groupid",hostGroupId))));return calls==1?first:second;}
        public String readText(String json){throw new AssertionError("No probe or arbitrary text read");}
    }
    static MappingDefinition mapping(){return new MappingDefinition("fixture-mapping","zabbix","fixture.cpu","fixture.cpu.usage.user","Fixture CPU",MetricType.GAUGE,"1",MetricValueType.DOUBLE,List.of("mode"),Map.of("mode","user"),"multiply:0.01",1,null,null);}
    static SourceMetricDiscovery read(Transport t){return new ZabbixMetricMetadataReader(new MappingRegistry(List.of(mapping()))).read(URI.create("http://127.0.0.1/api_jsonrpc.php"),t,"fixture-token");}
    static SourceMetricDiscovery read(Transport t,List<String> groups){return new ZabbixMetricMetadataReader(new MappingRegistry(List.of(mapping()))).read(URI.create("http://127.0.0.1/api_jsonrpc.php"),t,"fixture-token",groups);}
    @Test void matchedPageContainsOnlyAllowedMetadataAndExactMapping(){var t=new Transport();var d=read(t);assertEquals(2,t.calls);assertTrue(d.complete());assertEquals("FIRST_PAGE_MATCH",d.scanConsistency());assertEquals("MAPPED",d.items().getFirst().mappingStatus());assertEquals("fixture.cpu.usage.user",d.items().getFirst().mapping().metricKey());assertFalse(d.toString().contains("private-fixture"));assertEquals(SourceMetricDiscovery.Mapping.from(mapping()),d.items().getFirst().mapping());assertEquals(SourceMetricDiscovery.digest(d.items()),d.fingerprint());}
    @Test void sentinelAndChangingMetadataStayPartial(){var t=new Transport();t.first=java.util.stream.IntStream.rangeClosed(1,21).mapToObj(n->item(""+n,"fixture.unmapped."+n,"3")).toList();t.second=t.first;var d=read(t);assertEquals(20,d.items().size());assertFalse(d.complete());assertEquals("INCOMPLETE",d.statusCode());assertEquals("FIRST_PAGE_MATCH",d.scanConsistency());t=new Transport();t.second=List.of(item("1","fixture.cpu","4"));d=read(t);assertFalse(d.complete());assertEquals("UNVERIFIED",d.scanConsistency());}
    @Test void unmappedUnsupportedAndWrongTypesArePreserved(){var t=new Transport();t.first=List.of(item("1","fixture.cpu","4"),item("2","fixture.unmapped","5"),item("3","fixture.unknown","99"));t.second=t.first;var d=read(t);assertEquals("TYPE_MISMATCH",d.items().get(0).mappingStatus());assertEquals("BINARY",d.items().get(1).sourceValueType());assertEquals("NO_MAPPING",d.items().get(1).mappingStatus());assertEquals("UNKNOWN",d.items().get(2).sourceValueType());}
    @Test void invalidBudgetMissingFieldsDuplicateAndReorderedIdsFailClosed(){for(var rows:List.of(Collections.nCopies(22,item("1","x","0")),List.of(item("1","x","0"),item("1","x","0")),List.of(item("2","x","0"),item("1","x","0")),List.<Map<String,Object>>of(Map.of("itemid","1")))){var t=new Transport();t.first=rows;assertThrows(RuntimeException.class,()->read(t));assertEquals(1,t.calls);}}
    @Test void emptySuccessAndUnavailableHaveDistinctStatus(){var t=new Transport();t.first=List.of();t.second=List.of();var d=read(t);assertTrue(d.complete());assertEquals("READ_VERIFIED",d.statusCode());assertTrue(d.items().isEmpty());assertEquals("UNREACHABLE",SourceMetricDiscovery.unavailable().statusCode());}
    @Test void scopedMetadataIndependentlyChecksHostGroupsAndRejectsOutOfScopeHost(){var t=new Transport();var d=read(t,List.of("91"));assertTrue(d.complete());assertEquals(3,t.calls);var verify=CatalogJson.JSON.readTree(t.requests.get(2)).get("params");assertEquals("101",verify.get("hostids").get(0).asString());t=new Transport();t.hostGroupId="92";var outOfScope=t;assertThrows(IllegalStateException.class,()->read(outOfScope,List.of("91")));assertEquals(3,outOfScope.calls);}
}
