package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.platform.telemetry.MetricMappingJson;
import com.acme.opsweave.integration.domain.ZabbixItemMapper;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.MetricBinding;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Actual loopback HTTP/PG, isolated synthetic tenant, no source network or metric point writes. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","opsweave.auth.mode=dev","opsweave.auth.dev.subject=mapping-http-fixture","opsweave.auth.dev.permissions=metric.read,entity.read,source.sync,source.configure","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=mapping-http-fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class MetricMappingHttpIT {
    static final String TOKEN="mapping-http-fixture-"+UUID.randomUUID(),TENANT="mapping-http-"+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.token",()->TOKEN);r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
    @LocalServerPort int port; @Autowired InventoryWiring wiring;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> raw(String path,String method,String body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));if(auth)b.header("Authorization","Bearer "+TOKEN);if(body!=null)b.header("Content-Type","application/json");return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> call(String path,String method,Object body)throws Exception{return raw(path,method,body==null?null:CatalogJson.JSON.writeValueAsString(body),true);}
    String seed() {
        String source="fixture-"+UUID.randomUUID();var registry=ClasspathMappingCatalog.load(getClass().getClassLoader());
        var mapped=new ZabbixItemMapper(registry).map(new TenantId(TENANT),source,Map.of("itemid","20001","hostid","10084","key_","system.cpu.util[,user]","name","HTTP Fixture","value_type","0","units","%"));
        var b=mapped.binding();wiring.metrics().upsert(mapped.definition());wiring.metrics().upsert(new MetricBinding(b.tenantId(),b.sourceType(),b.sourceInstanceId(),b.externalItemId(),b.entityId(),b.hostExternalId(),b.metricKey(),b.fixedDimensions(),b.sourceUnit(),b.valueTransform(),b.mappingRevision(),b.lifecycle(),b.version()));
        return "/api/v2/metric-bindings/"+source+"/20001";
    }
    Map<String,Object> command(String path)throws Exception { var view=CatalogJson.JSON.readTree(call(path,"GET",null).body()).get("view");return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID(),"expectedBindingVersion",1,"mappingPin",MetricMappingJson.pin(MetricMappingJson.pin(view.get("candidates").get(0).get("mappingPin"))))); }
    @Test void closedReadAndCasReturnOriginalImmutableReceipt()throws Exception {
        String path=seed();var read=call(path,"GET",null);assertEquals(200,read.statusCode());assertEquals("no-store",read.headers().firstValue("Cache-Control").orElse(""));
        var v=CatalogJson.JSON.readTree(read.body()).get("view");assertTrue(v.get("binding").get("mappingPin").isNull());assertEquals("system.cpu.util[,user]",v.get("candidates").get(0).get("sourceKey").asString());
        var c=command(path);var result=call(path+"/mapping","POST",c);assertEquals(200,result.statusCode(),result.body());var original=CatalogJson.JSON.readTree(result.body());assertEquals(2,original.get("receipt").get("binding").get("version").asInt());
        assertEquals(original,CatalogJson.JSON.readTree(call(path+"/mapping","POST",c).body()));assertEquals(original,CatalogJson.JSON.readTree(call(path+"/mapping/commands/"+c.get("requestId"),"GET",null).body()));
        var other=new LinkedHashMap<>(c);other.put("requestId",UUID.randomUUID());assertEquals(409,call(path+"/mapping","POST",other).statusCode());
        other=new LinkedHashMap<>(c);other.put("expectedBindingVersion",2);assertEquals(409,call(path+"/mapping","POST",other).statusCode());
    }
    @Test void forgedIdentityRulesPinsDuplicateBodiesAndOversizeFailBeforeMutation()throws Exception {
        String path=seed();var c=command(path);assertEquals(401,raw(path,"GET",null,false).statusCode());assertEquals(400,call(path+"?tenantId=forged","GET",null).statusCode());
        for(String key:List.of("tenantId","ownerId","rules","url","sourceKey","execute","credentialPin")){var forged=new LinkedHashMap<>(c);forged.put(key,"forged");assertEquals(400,call(path+"/mapping","POST",forged).statusCode(),key);}
        var fake=new LinkedHashMap<>(c);fake.put("mappingPin",Map.of("id","zabbix-cpu-user","revision",1,"digest","sha256:"+"0".repeat(64)));assertEquals(409,call(path+"/mapping","POST",fake).statusCode());
        String body=CatalogJson.JSON.writeValueAsString(c);assertEquals(400,raw(path+"/mapping","POST",body+" {}",true).statusCode());assertEquals(400,raw(path+"/mapping","POST",body.substring(0,body.length()-1)+",\"expectedBindingVersion\":1}",true).statusCode());assertEquals(400,raw(path+"/mapping","POST","x".repeat(65537),true).statusCode());
        assertTrue(CatalogJson.JSON.readTree(call(path,"GET",null).body()).get("view").get("binding").get("mappingPin").isNull());
    }
}
