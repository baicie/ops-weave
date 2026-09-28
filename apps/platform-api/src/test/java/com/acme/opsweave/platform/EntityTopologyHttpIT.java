package com.acme.opsweave.platform;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=topology-http-fixture-only-32-characters","opsweave.auth.dev.subject=topology-reader","opsweave.auth.dev.permissions=entity.read","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class EntityTopologyHttpIT {
 static final String TENANT="topology-http-"+UUID.randomUUID();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;
 HttpResponse<String> get(String id,String query,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/entities/"+id+"/topology"+query)).timeout(Duration.ofSeconds(10));if(auth)b.header("Authorization","Bearer topology-http-fixture-only-32-characters");return HttpClient.newHttpClient().send(b.GET().build(),HttpResponse.BodyHandlers.ofString());}
 @Test void currentReadonlyViewAndRequestBoundary()throws Exception{String id=UUID.randomUUID().toString();var ds=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));try(var c=ds.getConnection();var s=c.prepareStatement("INSERT INTO inventory.entity(tenant_id,id,entity_type,name,lifecycle,version,attributes,last_seen_at,last_seen_epoch_nanos) VALUES(?,?,'Host','Fixture HTTP topology','ACTIVE',1,'{\"dataMode\":\"labeled-fixture\"}',now(),1790553600000000000)")){s.setString(1,TENANT);s.setObject(2,UUID.fromString(id));s.executeUpdate();}var response=get(id,"",true);assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));var json=CatalogJson.JSON.readTree(response.body());assertEquals(id,json.get("centerId").asString());assertEquals(TENANT,json.get("tenantId").asString());assertEquals(1,json.get("nodes").size());assertEquals("fixture",json.get("nodes").get(0).get("dataMode").asString());assertEquals(0,json.get("edges").size());assertFalse(response.body().contains("sourceRef"));assertEquals(401,get(id,"",false).statusCode());assertEquals(404,get(UUID.randomUUID().toString(),"",true).statusCode());assertEquals(400,get(id,"?tenantId=forged",true).statusCode());assertEquals(400,get(id,"?limit=999",true).statusCode());assertEquals(400,get("bad","",true).statusCode());}
}
