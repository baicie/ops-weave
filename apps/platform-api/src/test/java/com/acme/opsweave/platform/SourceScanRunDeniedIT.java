package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** Reading entity data must not grant the source-level scan trace. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties={"opsweave.auth.mode=dev","opsweave.auth.dev.token=scan-run-denied-local-test-only-32-chars","opsweave.auth.dev.subject=scan-denied","opsweave.auth.dev.tenant=tenant-scan-run-denied","opsweave.auth.dev.permissions=entity.read","opsweave.zabbix.mode=fixture","opsweave.zabbix.source-instance-id=zabbix-1","opsweave.inventory.store=memory"})
class SourceScanRunDeniedIT {
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> call(String path)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15))
            .header("Authorization","Bearer scan-run-denied-local-test-only-32-chars").GET().build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    @Test void entityReadDoesNotGrantScanTraceOrUnstoredRunLookup()throws Exception{
        assertEquals(403,call("/api/v1/integrations/zabbix/hosts/runs").statusCode());
        assertEquals(403,call("/api/v1/integrations/zabbix/hosts/runs/"+UUID.randomUUID()).statusCode());
        assertEquals(403,call("/api/v1/integrations/zabbix/items/runs").statusCode());
        assertEquals(403,call("/api/v1/integrations/zabbix/items/runs/"+UUID.randomUUID()).statusCode());
    }
}
