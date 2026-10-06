package com.acme.opsweave.platform;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/** Real loopback HTTP/PG with synthetic credentials only; no upstream requests. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","opsweave.auth.mode=dev","opsweave.auth.dev.subject=credential-fixture-author","opsweave.auth.dev.permissions=entity.read,source.sync,source.configure","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres"})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SourceCredentialHttpIT {
 static final String TOKEN="credential-fixture-"+UUID.randomUUID(),TENANT="credential-http-fixture-"+UUID.randomUUID();static Path keyring;
 static Path keyring(){try{if(keyring==null)keyring=Files.writeString(Files.createTempFile("opsweave-credential-fixture-",".json"),"{\"activeKeyId\":\"fixture-http-key\",\"keys\":{\"fixture-http-key\":\""+Base64.getEncoder().encodeToString(new byte[32])+"\"}}");return keyring;}catch(Exception e){throw new IllegalStateException("Fixture keyring failed");}}
 @AfterAll static void cleanup()throws Exception{if(keyring!=null)Files.deleteIfExists(keyring);}
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.token",()->TOKEN);r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.credentials.keyring-path",()->keyring().toString());r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;final HttpClient http=HttpClient.newHttpClient();final String root="/api/v2/data-sources/credentials";
 HttpResponse<String> raw(String path,String method,String body,boolean auth)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));if(auth)b.header("Authorization","Bearer "+TOKEN);if(body!=null)b.header("Content-Type","application/json");b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return http.send(b.build(),HttpResponse.BodyHandlers.ofString());}
 HttpResponse<String> call(String path,String method,Object body)throws Exception{return raw(path,method,body==null?null:CatalogJson.JSON.writeValueAsString(body),true);}
 Map<String,Object> creation(){return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID(),"name","HTTP Fixture credential","secret","fixture-http-secret"));}
 void publicOnly(String body){for(String forbidden:List.of("fixture-http-secret","ciphertext","nonce","secretDigest","keyId","Authorization"))assertFalse(body.contains(forbidden));}
 @Test void createRenameRotateRevokeAndOriginalReceiptNeverExposeSecret()throws Exception{
  var page=call(root,"GET",null);assertEquals(200,page.statusCode());var capabilities=CatalogJson.JSON.readTree(page.body());assertTrue(capabilities.get("vaultAvailable").asBoolean());assertTrue(capabilities.get("canConfigure").asBoolean());assertTrue(capabilities.get("secretWriteAvailable").asBoolean());
  var c=creation();var id=c.get("requestId");var response=call(root,"POST",c);assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElse(""));publicOnly(response.body());var original=CatalogJson.JSON.readTree(response.body());assertEquals(original,CatalogJson.JSON.readTree(call(root,"POST",c).body()));
  var changed=new LinkedHashMap<>(c);changed.put("secret","different-fixture-secret");assertEquals(409,call(root,"POST",changed).statusCode());
  var rename=new LinkedHashMap<String,Object>();rename.put("requestId",UUID.randomUUID());rename.put("expectedEditVersion",1);rename.put("name","Renamed HTTP Fixture");rename.put("state","ACTIVE");rename.put("secret",null);response=call(root+"/"+id,"PATCH",rename);assertEquals(200,response.statusCode());assertEquals(1,CatalogJson.JSON.readTree(response.body()).get("receipt").get("credential").get("revision").asInt());
  rename.put("requestId",UUID.randomUUID());rename.put("expectedEditVersion",2);rename.put("secret","fixture-rotated-http-secret");response=call(root+"/"+id,"PATCH",rename);assertEquals(200,response.statusCode());publicOnly(response.body());assertEquals(2,CatalogJson.JSON.readTree(response.body()).get("receipt").get("credential").get("revision").asInt());assertEquals(original,CatalogJson.JSON.readTree(call(root+"/"+id+"/commands/"+id,"GET",null).body()));
  var revoke=Map.of("requestId",UUID.randomUUID(),"expectedEditVersion",3);assertEquals(200,call(root+"/"+id+"/versions/1/revoke","POST",revoke).statusCode());assertEquals(200,call(root+"/"+id+"/versions/1/revoke","POST",revoke).statusCode());response=call(root+"/"+id+"/versions","GET",null);assertEquals(200,response.statusCode());publicOnly(response.body());assertEquals(2,CatalogJson.JSON.readTree(response.body()).get("items").size());assertEquals(1,CatalogJson.JSON.readTree(response.body()).get("items").valueStream().filter(n->n.get("revoked").asBoolean()).count());
  rename.put("requestId",UUID.randomUUID());rename.put("expectedEditVersion",4);rename.put("state","REVOKED");rename.put("secret",null);assertEquals(200,call(root+"/"+id,"PATCH",rename).statusCode());rename.put("requestId",UUID.randomUUID());rename.put("expectedEditVersion",5);rename.put("state","ACTIVE");assertEquals(409,call(root+"/"+id,"PATCH",rename).statusCode());
 }
 @Test void malformedRequestsAndIdentityOrNetworkInjectionCannotWrite()throws Exception{
  assertEquals(401,raw(root,"POST",CatalogJson.JSON.writeValueAsString(creation()),false).statusCode());assertEquals(400,call(root+"?tenantId=forged","GET",null).statusCode());assertEquals(400,call(root+"/1-1-1-1-1","GET",null).statusCode());
  for(String key:List.of("tenantId","userId","url","keyId","secretDigest","ciphertext","sourceId")){var c=creation();c.put(key,"forged");assertEquals(400,call(root,"POST",c).statusCode(),key);}
  for(String secret:List.of("","with space","line\nfeed","x".repeat(4097),"非ASCII")){var c=creation();c.put("secret",secret);assertEquals(400,call(root,"POST",c).statusCode());}
  assertEquals(400,raw(root,"POST","{\"requestId\":\""+UUID.randomUUID()+"\",\"requestId\":\""+UUID.randomUUID()+"\",\"name\":\"Fixture\",\"secret\":\"fixture-secret\"}",true).statusCode());assertEquals(400,raw(root,"POST",CatalogJson.JSON.writeValueAsString(creation())+" {}",true).statusCode());assertEquals(400,raw(root,"POST","x".repeat(65537),true).statusCode());
 }
}
