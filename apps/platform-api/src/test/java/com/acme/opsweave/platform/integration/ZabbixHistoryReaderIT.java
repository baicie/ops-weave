package com.acme.opsweave.platform.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.integration.api.Connector.SourceContext;
import com.acme.opsweave.integration.domain.HistoryCursor;
import com.acme.opsweave.integration.domain.HistoryReadException;
import com.acme.opsweave.integration.domain.HistoryWindow;
import com.acme.opsweave.integration.domain.MappingDefinition;
import com.acme.opsweave.integration.domain.MappingRegistry;
import com.acme.opsweave.integration.domain.ZabbixItemMapper;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcHistoryReader;
import com.acme.opsweave.sharedkernel.TenantId;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ZabbixHistoryReaderIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TenantId TENANT = new TenantId("tenant-demo");
    private static final SourceContext SOURCE = new SourceContext(TENANT, "zabbix-1", "stub-secret-ref");

    @Test
    void readsSameSecondIncrementallyWithSourceTypeAndConfiguredConversion() throws Exception {
        try (Stub stub = new Stub()) {
            stub.rows = List.of(row("20001", 10, 100, "25"), row("20001", 10, 200, "30"), row("20001", 11, 0, "40"));
            var first = stub.read(new HistoryWindow(10, 20, null, 1));
            assertEquals("0.25", first.points().getFirst().value().toPlainString());
            assertEquals(new HistoryCursor(10, 100), first.nextCursor());
            assertFalse(first.windowComplete());
            var second = stub.read(new HistoryWindow(10, 20, first.nextCursor(), 5));
            assertEquals(2, second.points().size());
            assertEquals(200, second.points().getFirst().timestamp().getNano());
            assertTrue(second.windowComplete());
            assertEquals(new HistoryCursor(20, 999_999_999), second.nextCursor());
            assertEquals(4, stub.requests.size());
            JsonNode params = stub.requests.get(1).get("params");
            assertEquals("history.get", stub.requests.get(1).get("method").asString());
            assertEquals(0, params.get("history").asInt());
            assertEquals("20001", params.get("itemids").get(0).asString());
            assertEquals("ns", params.get("sortfield").get(1).asString());
            assertEquals("ASC", params.get("sortorder").get(1).asString());
            assertEquals(501, params.get("limit").asInt());
            assertFalse(params.has("offset"));
            assertEquals("Bearer labeled-protocol-stub-token", stub.authorization);
            assertFalse(stub.requests.getFirst().toString().contains("token"));
        }
    }

    @Test
    void choosesUnsignedHistoryFromSourceEvenWhenCatalogUsesDouble() throws Exception {
        try (Stub stub = new Stub()) {
            stub.type = "3";
            stub.unboundedTestMapping = true;
            stub.rows = List.of(row("20001", 10, 0, "18446744073709551615"));
            var page = stub.read(new HistoryWindow(10, 20, null, 10));
            assertEquals(3, stub.requests.get(1).get("params").get("history").asInt());
            assertEquals("184467440737095516.15", page.points().getFirst().value().toPlainString());
            stub.rows = List.of(row("20001", 10, 0, "18446744073709551616"));
            assertCode("INVALID_SOURCE_RESPONSE", stub);
            stub.rows = List.of(row("20001", 10, 0, "-1"));
            assertCode("INVALID_SOURCE_RESPONSE", stub);
        }
    }

    @Test
    void rejectsMalformedForeignAndUnorderedRowsWithoutPartialResult() throws Exception {
        try (Stub stub = new Stub()) {
            for (var invalid : List.of(
                row("another-item", 10, 0, "1"), row("20001", 10, -1, "1"),
                row("20001", 10, 1_000_000_000, "1"), row("20001", 21, 0, "1"),
                row("20001", 10, 0, "NaN"), row("20001", 10, 0, "1e999"),
                row("20001", 10, 0, "101"), row("20001", 10, 0, "-1")
            )) {
                stub.rows = List.of(invalid);
                assertCode("INVALID_SOURCE_RESPONSE", stub);
            }
            stub.rows = List.of(row("20001", 10, 2, "1"), row("20001", 10, 1, "2"));
            assertCode("INVALID_SOURCE_RESPONSE", stub);
            stub.rows = List.of(row("20001", 10, 1, "1"), row("20001", 10, 1, "2"));
            assertCode("INVALID_SOURCE_RESPONSE", stub);
        }
    }

    @Test
    void rejectsDenseSecondChangedMetadataAndUpstreamFailure() throws Exception {
        try (Stub stub = new Stub()) {
            List<Map<String, Object>> dense = new ArrayList<>();
            for (int i = 0; i < 501; i++) dense.add(row("20001", 10, i, "1"));
            stub.rows = dense;
            assertCode("HISTORY_SECOND_LIMIT", stub);
            stub.host = "10085";
            assertCode("METADATA_CHANGED", stub);
            stub.host = "10084";
            stub.type = "1";
            assertCode("UNSUPPORTED_HISTORY", stub);
            stub.type = "0";
            stub.error = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"private-source-detail\"}}";
            assertCode("SOURCE_FETCH_FAILED", stub);
            stub.status = 503;
            assertCode("SOURCE_FETCH_FAILED", stub);
        }
    }

    @Test
    void boundsHttpResponseBytesAndRejectsMalformedEnvelope() throws Exception {
        try (Stub stub = new Stub()) {
            stub.error = "x".repeat(JacksonZabbixTransport.MAX_RESPONSE_BYTES + 1);
            assertCode("SOURCE_FETCH_FAILED", stub);
            var transport = new JacksonZabbixTransport();
            for (String bad : List.of("null", "[]", "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":[]}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[null]}")) {
                assertThrows(IllegalStateException.class, () -> transport.readHostArray(bad));
            }
        }
    }

    private static void assertCode(String code, Stub stub) {
        var error = assertThrows(HistoryReadException.class, () -> stub.read(new HistoryWindow(10, 20, null, 500)));
        assertEquals(code, error.code().name());
        assertEquals(code, error.getMessage());
    }

    private static Map<String, Object> row(String item, long clock, int ns, String value) {
        return Map.of("itemid", item, "clock", Long.toString(clock), "ns", Integer.toString(ns), "value", value);
    }

    /** Local protocol stub, not a vendor Zabbix instance. */
    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        volatile List<Map<String, Object>> rows = List.of();
        volatile String type = "0";
        volatile String host = "10084";
        volatile String error;
        volatile String authorization;
        volatile int status = 200;
        boolean unboundedTestMapping;

        Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api_jsonrpc.php", exchange -> {
                try {
                    authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    requests.add(request);
                    Object result = "item.get".equals(request.get("method").asString()) ? List.of(item(type, host)) : rows;
                    byte[] body = (error == null ? JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "result", result)) : error)
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        com.acme.opsweave.integration.domain.HistoryPage read(HistoryWindow window) {
            var mappings = ClasspathMappingCatalog.load(getClass().getClassLoader());
            if (unboundedTestMapping) {
                var m = mappings.find("zabbix", "system.cpu.util[,user]").orElseThrow();
                mappings = new MappingRegistry(List.of(new MappingDefinition(m.id(), m.connector(), m.itemKeyExact(),
                    m.metricKey(), m.displayName(), m.metricType(), m.unit(), m.valueType(), m.dimensionSchema(),
                    m.fixedDimensions(), m.valueTransform(), m.mappingRevision(), null, null)));
            }
            var binding = new ZabbixItemMapper(mappings).map(TENANT, "zabbix-1", item("0", "10084")).binding();
            var reader = new ZabbixJsonRpcHistoryReader(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api_jsonrpc.php"),
                new JacksonZabbixTransport(), ignored -> "labeled-protocol-stub-token", mappings);
            return reader.read(SOURCE, binding, window);
        }

        private Map<String, Object> item(String valueType, String hostId) {
            return Map.of("itemid", "20001", "key_", "system.cpu.util[,user]", "name", "CPU user",
                "hostid", hostId, "units", "%", "value_type", valueType);
        }

        @Override public void close() { server.stop(0); }
    }
}
