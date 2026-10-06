package com.acme.opsweave.platform;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","opsweave.auth.mode=dev","opsweave.auth.dev.subject=credential-read-fixture","opsweave.auth.dev.permissions=source.sync","opsweave.zabbix.mode=fixture","opsweave.inventory.store=postgres","opsweave.credentials.keyring-path="})
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class SourceCredentialReadOnlyHttpIT {
 static final String TOKEN="credential-read-fixture-"+UUID.randomUUID(),TENANT="credential-read-fixture-"+UUID.randomUUID();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("opsweave.auth.dev.token",()->TOKEN);r.add("opsweave.auth.dev.tenant",()->TENANT);r.add("opsweave.inventory.jdbc-url",()->System.getenv("OPSWEAVE_TEST_JDBC_URL"));r.add("opsweave.inventory.jdbc-user",()->System.getenv("OPSWEAVE_TEST_JDBC_USER"));r.add("opsweave.inventory.jdbc-password",()->System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));}
 @LocalServerPort int port;
 @Test void readCapabilityDoesNotGrantConfigurationAndNoKeyringIsExplicit()throws Exception{
  var client=HttpClient.newHttpClient();String base="http://127.0.0.1:"+port+"/api/v2/data-sources/credentials";var response=client.send(HttpRequest.newBuilder(URI.create(base)).header("Authorization","Bearer "+TOKEN).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());var page=CatalogJson.JSON.readTree(response.body());assertFalse(page.get("canConfigure").asBoolean());assertFalse(page.get("vaultAvailable").asBoolean());assertFalse(page.get("secretWriteAvailable").asBoolean());
  response=client.send(HttpRequest.newBuilder(URI.create(base)).header("Authorization","Bearer "+TOKEN).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(CatalogJson.JSON.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"name","Read-only Fixture","secret","fixture-value")))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,response.statusCode());assertEquals("CREDENTIAL_FORBIDDEN",CatalogJson.JSON.readTree(response.body()).get("error").asString());
 }
}
