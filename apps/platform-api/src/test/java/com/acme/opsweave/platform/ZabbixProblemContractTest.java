package com.acme.opsweave.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcProblemReader;
import com.acme.opsweave.platform.integration.JacksonZabbixTransport;
import com.acme.opsweave.sharedkernel.TenantId;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Local protocol stub, not a vendor instance acceptance. */
class ZabbixProblemContractTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private static final Connector.SourceContext SOURCE = new Connector.SourceContext(new TenantId("tenant-test"), "zabbix-test", "env:TEST");
    @Test void sendsOnlyBoundedReadParametersAndFetchesRecoveryByExactId() throws Exception {
        List<JsonNode> requests = new ArrayList<>(); List<String> authorization = new ArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api_jsonrpc.php", exchange -> {
            var request = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request); authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String result = request.get("params").has("eventids") ? """
                [{"eventid":"201","source":"0","object":"0","value":"0","clock":"950","ns":"7"}]
                """ : """
                [{"eventid":"101","source":"0","object":"0","objectid":"50","clock":"900","ns":"42","value":"1",
                "name":"CPU threshold exceeded","severity":"4","r_eventid":"201","suppressed":"0","hosts":[{"hostid":"10084"}]}]
                """;
            byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            var page = reader(server).read(SOURCE, new ProblemReadWindow(800, 1000, "100", 25));
            assertEquals(ExternalProblem.State.RECOVERED, page.items().getFirst().state()); assertEquals(2, requests.size());
            assertEquals(List.of("Bearer protocol-test-secret", "Bearer protocol-test-secret"), authorization);
            var first = requests.getFirst(); assertEquals("event.get", first.get("method").asString());
            assertFalse(first.toString().contains("protocol-test-secret"));
            var params = first.get("params"); assertEquals("101", params.get("eventid_from").asString()); assertEquals(25, params.get("limit").asInt());
            assertEquals(800, params.get("problem_time_from").asInt()); assertEquals(1000, params.get("problem_time_till").asInt());
            assertFalse(params.has("time_from")); assertFalse(params.has("selectAcknowledges"));
            assertEquals("201", requests.getLast().get("params").get("eventids").get(0).asString());
            assertEquals(1, requests.getLast().get("params").get("limit").asInt());
        } finally { server.stop(0); }
    }
    @Test void vendorErrorsAndOversizedBodiesFailWithoutADataPage() throws Exception {
        for (String result : List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"private vendor detail\"}}", "x".repeat(JacksonZabbixTransport.MAX_RESPONSE_BYTES + 1))) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api_jsonrpc.php", exchange -> {
                byte[] body = result.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, body.length);
                try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
            }); server.start();
            try {
                var error = assertThrows(ProblemReadException.class, () -> reader(server).read(SOURCE, new ProblemReadWindow(800, 1000, null, 25)));
                assertEquals(result.length() > JacksonZabbixTransport.MAX_RESPONSE_BYTES ? ProblemReadException.Code.SOURCE_FETCH_FAILED : ProblemReadException.Code.INVALID_SOURCE_RESPONSE, error.code());
                assertFalse(error.getMessage().contains("private")); assertNull(error.getCause());
            } finally { server.stop(0); }
        }
    }
    private ZabbixJsonRpcProblemReader reader(HttpServer server) {
        return new ZabbixJsonRpcProblemReader(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api_jsonrpc.php"),
            new JacksonZabbixTransport(), ref -> "protocol-test-secret", Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
    }
}
