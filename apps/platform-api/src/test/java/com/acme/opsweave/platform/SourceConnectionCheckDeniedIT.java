package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** Reading entity data must not grant a source self-check. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=connection-check-denied-local-test-32-chars","opsweave.auth.dev.subject=connection-denied","opsweave.auth.dev.tenant=tenant-connection-denied","opsweave.auth.dev.permissions=entity.read","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=memory"})
class SourceConnectionCheckDeniedIT {
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> call(String method)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/integrations/zabbix/connection-checks")).timeout(Duration.ofSeconds(15))
            .header("Authorization","Bearer connection-check-denied-local-test-32-chars");
        return http.send("POST".equals(method)?builder.POST(HttpRequest.BodyPublishers.noBody()).build():builder.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void entityReadDoesNotGrantASourceSelfCheck()throws Exception{
        assertEquals(403,call("POST").statusCode());
        assertEquals(403,call("GET").statusCode());
    }
}
