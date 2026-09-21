package com.acme.opsweave.platform.integration;

import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class JacksonZabbixTransport implements ZabbixJsonRpcConnector.Transport {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public String exchange(URI endpoint, String jsonBody, String bearerToken) {
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalStateException("Zabbix endpoint must be http or https");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Zabbix HTTP status " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Zabbix JSON-RPC transport interrupted");
        } catch (IOException failed) {
            throw new IllegalStateException("Zabbix JSON-RPC transport failed");
        }
    }

    @Override
    public List<Map<String, Object>> readHostArray(String responseJson) {
        JsonNode root = mapper.readTree(responseJson);
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException("Zabbix JSON-RPC error");
        }
        JsonNode result = root.get("result");
        if (result == null || !result.isArray()) {
            throw new IllegalStateException("Zabbix host.get did not return an array");
        }
        List<Map<String, Object>> hosts = new ArrayList<>();
        for (JsonNode node : result) {
            hosts.add(toMap(node));
        }
        return List.copyOf(hosts);
    }

    private Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        node.properties().forEach(entry -> map.put(entry.getKey(), toValue(entry.getValue())));
        return map;
    }

    private Object toValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(toValue(item)));
            return values;
        }
        if (node.isObject()) {
            return toMap(node);
        }
        if (node.isNumber()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return Boolean.toString(node.asBoolean());
        }
        return node.asText();
    }
}
