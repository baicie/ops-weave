package com.acme.opsweave.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.sharedkernel.TenantId;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ZabbixJsonRpcConnectorIT {
    @Test
    void hostGetAgainstLocalProtocolStub() throws Exception {
        var countRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api_jsonrpc.php", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] request = exchange.getRequestBody().readAllBytes();
            String requestText = new String(request, StandardCharsets.UTF_8);
            byte[] body;
            int status;
            if (!"Bearer stub-token-not-from-a-vendor-zabbix".equals(auth)) {
                body = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"denied\"},\"id\":1}"
                    .getBytes(StandardCharsets.UTF_8);
                status = 401;
            } else if (requestText.contains("\"countOutput\":true")) {
                countRequests.incrementAndGet();
                body = "{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":1}".getBytes(StandardCharsets.UTF_8);
                status = 200;
            } else if (requestText.contains("\"sortorder\":\"DESC\"")) {
                body = (requestText.contains("\"output\":[\"hostid\"]")
                    ? "{\"jsonrpc\":\"2.0\",\"result\":[{\"hostid\":\"10084\"}],\"id\":1}"
                    : "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"bad bound\"},\"id\":1}")
                    .getBytes(StandardCharsets.UTF_8);
                status = requestText.contains("\"output\":[\"hostid\"]") ? 200 : 400;
            } else if (requestText.contains("\"sortfield\":\"hostid\"") && requestText.contains("\"offset\":0")) {
                body = ("{\"jsonrpc\":\"2.0\",\"result\":[{"
                    + "\"hostid\":\"10084\",\"host\":\"stub-host\",\"name\":\"Stub Host\",\"status\":\"0\","
                    + "\"interfaces\":[{\"ip\":\"10.1.2.3\",\"main\":\"1\",\"type\":\"1\"}]}],\"id\":1}")
                    .getBytes(StandardCharsets.UTF_8);
                status = 200;
            } else {
                body = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"bad page\"},\"id\":1}"
                    .getBytes(StandardCharsets.UTF_8);
                status = 400;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api_jsonrpc.php");
            var connector = new ZabbixJsonRpcConnector(
                endpoint,
                new JacksonZabbixTransport(),
                secretRef -> "stub-token-not-from-a-vendor-zabbix"
            );
            Connector.Page page = connector.fetch(
                new Connector.SourceContext(new TenantId("tenant-demo"), "zabbix-1", "env:OPSWEAVE_ZABBIX_TOKEN"),
                null,
                50
            );
            assertEquals(1, page.records().size());
            assertEquals("10084", page.records().get(0).externalId());
            assertEquals("Stub Host", page.records().get(0).payload().get("name"));
            assertTrue(page.snapshotComplete(), "the walk completes when the captured count and watermark both hold");
            assertEquals("hostid-watermark-snapshot", page.scanConsistency());
            assertEquals(1, countRequests.get(), "the row count is captured once before the first page");
        } finally {
            server.stop(0);
        }
    }
}
