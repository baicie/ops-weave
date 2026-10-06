package com.acme.opsweave.platform.workflow;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisteredSourceEndpointsTest {
 @TempDir Path dir;
 static final OpsweaveProperties DEV=new OpsweaveProperties(new OpsweaveProperties.Auth("dev",true,null),null,null);
 static final OpsweaveProperties CLOSED=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,null),null,null);
 Path file(String text)throws Exception{return Files.writeString(dir.resolve(UUID.randomUUID()+".json"),text);}
 Map<String,Object> row(String id,String address,String... tenants){return Map.of("id",id,"name","Fixture endpoint","connectorKind","ZABBIX_HOST","address",address,"tenants",List.of(tenants));}
 String body(Object... rows){return CatalogJson.JSON.writeValueAsString(Map.of("schemaVersion","2.0","endpoints",List.of(rows)));}
 @Test void numericUnicastHttpsAndExplicitLoopbackDevelopmentHttpAreAccepted(){
  for(String address:List.of("https://192.0.2.10/api_jsonrpc.php","https://10.0.0.20:8443/api_jsonrpc.php","https://[2001:db8::1]/api_jsonrpc.php","https://[fd00::1]:8443/api_jsonrpc.php"))assertEquals(address,RegisteredSourceEndpoints.validateAddress(address,CLOSED).toString());
  for(String address:List.of("http://127.0.0.1:18088/api_jsonrpc.php","https://127.0.0.2/api_jsonrpc.php","http://[::1]:18088/api_jsonrpc.php"))assertEquals(address,RegisteredSourceEndpoints.validateAddress(address,DEV).toString());
 }
 @Test void ambiguousDnsCredentialUrlAndUnsafeRangesCannotBecomeDestinations(){
  for(String address:List.of("http://192.0.2.10/api_jsonrpc.php","https://127.0.0.1/api_jsonrpc.php","https://[::1]/api_jsonrpc.php","https://localhost/api_jsonrpc.php","https://fixture.invalid/api_jsonrpc.php","https://2130706433/api_jsonrpc.php","https://127.1/api_jsonrpc.php","https://0177.0.0.1/api_jsonrpc.php","https://256.0.0.1/api_jsonrpc.php","https://0.0.0.0/api_jsonrpc.php","https://0.1.2.3/api_jsonrpc.php","https://169.254.169.254/api_jsonrpc.php","https://224.0.0.1/api_jsonrpc.php","https://255.255.255.255/api_jsonrpc.php","https://[::]/api_jsonrpc.php","https://[fe80::1]/api_jsonrpc.php","https://[ff02::1]/api_jsonrpc.php","https://[::ffff:127.0.0.1]/api_jsonrpc.php","https://[fe80::1%25eth0]/api_jsonrpc.php","https://user:fixture@192.0.2.1/api_jsonrpc.php","https://192.0.2.1/api_jsonrpc.php?token=fixture","https://192.0.2.1/api_jsonrpc.php#fixture","https://192.0.2.1/other","https://192.0.2.1/%61pi_jsonrpc.php","https://192.0.2.1:0/api_jsonrpc.php","https://192.0.2.1:65536/api_jsonrpc.php","https://192.0.2.1\\@127.0.0.1/api_jsonrpc.php","HTTPS://192.0.2.1/api_jsonrpc.php"," https://192.0.2.1/api_jsonrpc.php","https://192.0.2.1/api_jsonrpc.php\n"))assertThrows(IllegalArgumentException.class,()->RegisteredSourceEndpoints.validateAddress(address,CLOSED),address);
  for(String address:List.of("https://192.0.2.1:/api_jsonrpc.php","https://192.0.2.1:00080/api_jsonrpc.php","https://[2001:db8::1]:/api_jsonrpc.php"))assertThrows(IllegalArgumentException.class,()->RegisteredSourceEndpoints.validateAddress(address,CLOSED));
  var publicDev=new OpsweaveProperties(new OpsweaveProperties.Auth("dev",false,null),null,null);assertThrows(IllegalArgumentException.class,()->RegisteredSourceEndpoints.validateAddress("http://127.0.0.1/api_jsonrpc.php",publicDev));assertThrows(IllegalArgumentException.class,()->RegisteredSourceEndpoints.validateAddress("http://192.0.2.1/api_jsonrpc.php",DEV));
 }
 @Test void emptyAndScopedStartupRegistryNeverFallsBackOrHotReloads()throws Exception{
  assertTrue(new RegisteredSourceEndpoints("",DEV).list(new TenantId("fixture-a")).isEmpty());var path=file(body(row("fixture-a","https://192.0.2.1/api_jsonrpc.php","fixture-a"),row("fixture-b","https://192.0.2.2/api_jsonrpc.php","fixture-b","fixture-a")));var catalog=new RegisteredSourceEndpoints(path.toString(),DEV);
  assertEquals(2,catalog.list(new TenantId("fixture-a")).size());assertEquals(1,catalog.list(new TenantId("fixture-b")).size());assertTrue(catalog.list(new TenantId("fixture-other")).isEmpty());assertTrue(catalog.find(new TenantId("fixture-b"),"fixture-a").isEmpty());var original=catalog.find(new TenantId("fixture-a"),"fixture-a").orElseThrow();
  Files.writeString(path,body(row("fixture-a","https://192.0.2.3/api_jsonrpc.php","fixture-a")));assertEquals(original,catalog.find(new TenantId("fixture-a"),"fixture-a").orElseThrow());assertNotEquals(original.digest(),new RegisteredSourceEndpoints(path.toString(),DEV).find(new TenantId("fixture-a"),"fixture-a").orElseThrow().digest());assertFalse(catalog.toString().contains("192.0.2.1"));
 }
 @Test void malformedUnknownDuplicateAndOversizedConfigurationFailsWithoutLeakingContents()throws Exception{
  for(String text:List.of("{}","{} {}","{\"schemaVersion\":\"2.0\",\"schemaVersion\":\"2.0\",\"endpoints\":[]}",body(row("fixture-a","https://192.0.2.1/api_jsonrpc.php","fixture-a","fixture-a")),body(row("fixture-a","https://192.0.2.1/api_jsonrpc.php","fixture-a"),row("fixture-a","https://192.0.2.2/api_jsonrpc.php","fixture-a")),body(row("fixture-a","https://192.0.2.1/api_jsonrpc.php")),"x".repeat(65537))){var path=file(text);var e=assertThrows(IllegalStateException.class,()->new RegisteredSourceEndpoints(path.toString(),DEV));assertEquals("Source endpoint configuration is invalid",e.getMessage());assertNull(e.getCause());}
  var raw=new LinkedHashMap<>(row("fixture-a","https://192.0.2.1/api_jsonrpc.php","fixture-a"));raw.put("secret","fixture-secret");var path=file(body(raw));assertThrows(IllegalStateException.class,()->new RegisteredSourceEndpoints(path.toString(),DEV));
  var rows=new ArrayList<>();for(int n=0;n<33;n++)rows.add(row("fixture-"+n,"https://192.0.2.1/api_jsonrpc.php","fixture-a"));var oversized=file(CatalogJson.JSON.writeValueAsString(Map.of("schemaVersion","2.0","endpoints",rows)));assertThrows(IllegalStateException.class,()->new RegisteredSourceEndpoints(oversized.toString(),DEV));
 }
 @Test void fileMustExistBeAbsoluteAndStayOutsideRepositories()throws Exception{
  assertThrows(IllegalStateException.class,()->new RegisteredSourceEndpoints("relative.json",DEV));assertThrows(IllegalStateException.class,()->new RegisteredSourceEndpoints(dir.resolve("missing.json").toString(),DEV));assertThrows(IllegalStateException.class,()->new RegisteredSourceEndpoints(dir.toString(),DEV));Files.createDirectory(dir.resolve(".git"));var path=file(body());assertThrows(IllegalStateException.class,()->new RegisteredSourceEndpoints(path.toString(),DEV));
 }
}
