package com.acme.opsweave.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcItemConnector;
import com.acme.opsweave.sharedkernel.TenantId;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ZabbixJsonRpcConnectorIT {
    @Test
    void versionDiscoveryOmitsAuthorizationForBothConnectors() throws Exception {
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api_jsonrpc.php", exchange -> {
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            assertTrue(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).contains("apiinfo.version"));
            requests.incrementAndGet();
            byte[] body = "{\"jsonrpc\":\"2.0\",\"result\":\"7.0.27\",\"id\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api_jsonrpc.php");
            var transport = new JacksonZabbixTransport();
            var source = new Connector.SourceContext(new TenantId("tenant-demo"), "zabbix-1", "env:OPSWEAVE_ZABBIX_TOKEN");
            var host = new ZabbixJsonRpcConnector(endpoint, transport, ref -> "fixture-secret");
            var item = new ZabbixJsonRpcItemConnector(endpoint, transport, ref -> "fixture-secret");
            assertTrue(host.probe(source).reachable());
            assertTrue(item.probe(source).reachable());
            assertEquals(2, requests.get());
            assertThrows(IllegalStateException.class, () -> transport.exchange(endpoint,
                "{\"jsonrpc\":\"2.0\",\"method\":\"host.get\",\"params\":{},\"id\":1}", null));
            assertEquals(2, requests.get(), "anonymous business requests must fail before sending");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void countOutputAcceptsBoundedDecimalStringsWithoutLossyCoercion() {
        var transport = new JacksonZabbixTransport();
        assertEquals(1, transport.readCount("{\"jsonrpc\":\"2.0\",\"result\":\"1\",\"id\":1}"));
        assertEquals(0, transport.readCount("{\"jsonrpc\":\"2.0\",\"result\":\"0\",\"id\":1}"));
        assertEquals(1, transport.readCount("{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":1}"));
        assertEquals(Long.MAX_VALUE, transport.readCount("{\"jsonrpc\":\"2.0\",\"result\":\"9223372036854775807\",\"id\":1}"));
        for (String result : new String[] {"null", "true", "[]", "{}", "-1", "1.5", "9223372036854775808",
            "\"\"", "\" 1\"", "\"1 \"", "\"01\"", "\"-1\"", "\"+1\"", "\"1.0\"", "\"1e1\"", "\"9223372036854775808\""}) {
            assertThrows(IllegalStateException.class, () -> transport.readCount(
                "{\"jsonrpc\":\"2.0\",\"result\":" + result + ",\"id\":1}"), result);
        }
    }

    @Test
    void hostGetAgainstLocalProtocolStub() throws Exception {
        var countRequests = new AtomicInteger();
        var scopedRequests = new AtomicInteger();
        var outsideScope = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api_jsonrpc.php", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] request = exchange.getRequestBody().readAllBytes();
            String requestText = new String(request, StandardCharsets.UTF_8);
            if (requestText.contains("\"groupids\":[\"91\"]")) scopedRequests.incrementAndGet();
            byte[] body;
            int status;
            if (!"Bearer stub-token-not-from-a-vendor-zabbix".equals(auth)) {
                body = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"denied\"},\"id\":1}"
                    .getBytes(StandardCharsets.UTF_8);
                status = 401;
            } else if (requestText.contains("\"countOutput\":true")) {
                countRequests.incrementAndGet();
                body = "{\"jsonrpc\":\"2.0\",\"result\":\"1\",\"id\":1}".getBytes(StandardCharsets.UTF_8);
                status = 200;
            } else if (requestText.contains("\"output\":[\"hostid\"]")) {
                String groupId = outsideScope.get() ? "92" : "91";
                body = (requestText.contains("\"output\":[\"hostid\"]")
                    ? "{\"jsonrpc\":\"2.0\",\"result\":[{\"hostid\":\"10084\",\"groups\":[{\"groupid\":\"" + groupId + "\"}]}],\"id\":1}"
                    : "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"bad bound\"},\"id\":1}")
                    .getBytes(StandardCharsets.UTF_8);
                status = requestText.contains("\"output\":[\"hostid\"]") ? 200 : 400;
            } else if (requestText.contains("\"sortfield\":\"hostid\"") && requestText.contains("\"hostids\":[10084]") && !requestText.contains("\"offset\"")) {
                body = ("{\"jsonrpc\":\"2.0\",\"result\":[{"
                    + "\"hostid\":\"10084\",\"host\":\"stub-host\",\"name\":\"Stub Host\",\"status\":\"0\","
                    + "\"groups\":[{\"groupid\":\"" + (outsideScope.get() ? "92" : "91") + "\"}],"
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
                secretRef -> "stub-token-not-from-a-vendor-zabbix",
                java.util.List.of("91")
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
            assertEquals(2, countRequests.get(), "membership is rechecked before completion");
            assertEquals(5, scopedRequests.get(), "count, both manifests and the data page share the fixed host-group scope");
            outsideScope.set(true);
            assertThrows(IllegalStateException.class, () -> connector.fetch(
                new Connector.SourceContext(new TenantId("tenant-demo"), "zabbix-1", "env:OPSWEAVE_ZABBIX_TOKEN"), null, 50));
        } finally {
            server.stop(0);
        }
    }
}
