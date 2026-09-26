package com.acme.opsweave.ingestion.history;

import com.acme.opsweave.integration.api.HistoryIngestionPorts.*;
import com.acme.opsweave.integration.api.HistoryIngestionPorts.Failure.Code;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.telemetry.domain.MetricPoint;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class PlatformHistoryReader implements SourceReader {
    private static final Set<String> FIELDS = Set.of("dataMode", "persistence", "tenantId", "sourceInstanceId", "externalItemId",
        "entityId", "metricKey", "unit", "dimensions", "mappingRevision", "definitionVersion", "bindingVersion",
        "from", "till", "points", "nextCursor", "windowComplete");
    private final LoopbackHttp http;
    private final HistoryAuthorization authorization;
    private final String pathPrefix;
    private final String expectedDataMode;
    private final String checkpointIdentity;
    private final JsonMapper json = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public PlatformHistoryReader(URI origin, String token, String expectedDataMode) {
        this(origin, development(token), expectedDataMode, true, false);
    }
    public PlatformHistoryReader(URI origin, HistoryAuthorization authorization, String expectedDataMode, boolean loopbackTest) {
        this(origin, authorization, expectedDataMode, loopbackTest, true);
    }
    private static HistoryAuthorization development(String token) {
        if (token == null || token.length() < 32 || token.length() > 4096 || token.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("Invalid explicit development credential");
        return () -> "Bearer " + token;
    }
    private PlatformHistoryReader(URI origin, HistoryAuthorization authorization, String expectedDataMode, boolean loopbackTest, boolean service) {
        this.http = new LoopbackHttp(origin, loopbackTest);
        if (authorization == null || !Set.of("labeled-fixture", "zabbix-jsonrpc").contains(expectedDataMode)) {
            throw new IllegalArgumentException("History worker requires an explicit credential and source mode");
        }
        this.authorization = authorization;
        this.pathPrefix = service ? "/api/v1/service/ingestion/items/" : "/api/v1/integrations/zabbix/items/";
        this.expectedDataMode = expectedDataMode;
        this.checkpointIdentity = origin.toASCIIString() + "#" + expectedDataMode + (service ? "#client-credentials-v1#" + authorization.checkpointIdentity() : "");
    }

    @Override public String checkpointIdentity() { return checkpointIdentity; }

    @Override
    public Slice read(HistoryStream stream, HistoryWindow window) {
        String path = pathPrefix + stream.itemId() + "/history?from=" + window.from()
            + "&till=" + window.till() + "&limit=" + window.limit();
        if (window.after() != null) path += "&afterClock=" + window.after().clock() + "&afterNs=" + window.after().ns();
        String body;
        try {
            var response = http.request("GET", path, null, Map.of("Authorization", authorization.authorization()));
            if (response.statusCode() == 401) authorization.unauthorized();
            if (response.statusCode() != 200) throw new Failure(Code.SOURCE_FAILED);
            body = response.body();
        } catch (RuntimeException failed) { throw new Failure(Code.SOURCE_FAILED); }
        try { return decode(stream, window, json.readTree(body)); }
        catch (RuntimeException invalid) { throw new Failure(Code.INVALID_PAGE); }
    }

    private Slice decode(HistoryStream stream, HistoryWindow window, JsonNode root) {
        exactFields(root, FIELDS);
        if (!"not-persisted".equals(text(root, "persistence")) || !expectedDataMode.equals(text(root, "dataMode"))
            || !stream.tenantId().value().equals(text(root, "tenantId")) || !stream.sourceInstanceId().equals(text(root, "sourceInstanceId"))
            || !stream.itemId().equals(text(root, "externalItemId")) || number(root, "from") != window.from() || number(root, "till") != window.till()) {
            throw new Failure(Code.INVALID_PAGE);
        }
        String entity = text(root, "entityId");
        if (!UUID.fromString(entity).toString().equals(entity)) throw new Failure(Code.INVALID_PAGE);
        var labels = new TreeMap<String, String>();
        labels.put("tenant_id", stream.tenantId().value()); labels.put("source_instance_id", stream.sourceInstanceId());
        labels.put("external_item_id", stream.itemId()); labels.put("entity_id", entity);
        labels.put("metric_key", text(root, "metricKey")); labels.put("unit", text(root, "unit"));
        labels.put("mapping_revision", Long.toString(positive(root, "mappingRevision"))); labels.put("data_mode", expectedDataMode);
        JsonNode dimensions = root.get("dimensions");
        if (!dimensions.isObject() || dimensions.size() > 16) throw new Failure(Code.INVALID_PAGE);
        dimensions.properties().forEach(entry -> {
            if (!entry.getValue().isString()) throw new Failure(Code.INVALID_PAGE);
            labels.put("dimension_" + entry.getKey(), entry.getValue().asString());
        });
        JsonNode rows = root.get("points");
        if (!rows.isArray() || rows.size() > window.limit()) throw new Failure(Code.INVALID_PAGE);
        var points = new ArrayList<MetricPoint>();
        for (JsonNode row : rows) {
            exactFields(row, Set.of("clock", "ns", "value"));
            String value = text(row, "value");
            if (value.length() > 700 || !value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw new Failure(Code.INVALID_PAGE);
            points.add(new MetricPoint(cursor(row).instant(), new BigDecimal(value)));
        }
        JsonNode next = root.get("nextCursor");
        exactFields(next, Set.of("clock", "ns"));
        if (!root.get("windowComplete").isBoolean()) throw new Failure(Code.INVALID_PAGE);
        return new Slice(labels, positive(root, "definitionVersion"), positive(root, "bindingVersion"),
            new HistoryPage(points, cursor(next), root.get("windowComplete").asBoolean()));
    }

    private static HistoryCursor cursor(JsonNode node) { return new HistoryCursor(number(node, "clock"), Math.toIntExact(number(node, "ns"))); }
    private static long positive(JsonNode node, String key) { long value = number(node, key); if (value < 1) throw new Failure(Code.INVALID_PAGE); return value; }
    private static long number(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new Failure(Code.INVALID_PAGE);
        return value.asLong();
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString()) throw new Failure(Code.INVALID_PAGE);
        return value.asString();
    }
    private static void exactFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(expected)) throw new Failure(Code.INVALID_PAGE);
    }
}
