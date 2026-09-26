package com.acme.opsweave.platform.integration;

import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class JacksonZabbixTransport implements ZabbixJsonRpcConnector.Transport {
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
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
            HttpResponse<String> response = http.send(request, ignored -> new BoundedBodySubscriber());
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
        JsonNode root = envelope(responseJson);
        JsonNode result = root.get("result");
        if (result == null || !result.isArray()) {
            throw new IllegalStateException("Zabbix JSON-RPC did not return an array");
        }
        List<Map<String, Object>> hosts = new ArrayList<>();
        for (JsonNode node : result) {
            if (!node.isObject()) throw new IllegalStateException("Invalid Zabbix result row");
            hosts.add(toMap(node));
        }
        return List.copyOf(hosts);
    }

    @Override
    public long readCount(String responseJson) {
        JsonNode root = envelope(responseJson);
        JsonNode result = root.get("result");
        if (result == null || !result.isIntegralNumber()) {
            throw new IllegalStateException("Zabbix JSON-RPC did not return a count");
        }
        return result.asLong();
    }

    @Override
    public String readText(String responseJson) {
        JsonNode root = envelope(responseJson);
        JsonNode result = root.get("result");
        if (result == null || !result.isTextual()) {
            throw new IllegalStateException("Zabbix JSON-RPC did not return a text result");
        }
        String text = result.asText();
        if (!text.matches("[ -~]{1,32}")) {
            throw new IllegalStateException("Zabbix JSON-RPC text result is out of bounds");
        }
        return text;
    }

    private JsonNode envelope(String responseJson) {
        JsonNode root = mapper.readTree(responseJson);
        if (root == null || !root.isObject() || !"2.0".equals(root.path("jsonrpc").asText())
            || !root.path("id").isIntegralNumber() || root.path("id").asLong() != 1) {
            throw new IllegalStateException("Invalid Zabbix JSON-RPC envelope");
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException("Zabbix JSON-RPC error");
        }
        return root;
    }

    /** Cancel during receipt, before an untrusted body can grow without bound. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private long received;
        private boolean failed;

        @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > MAX_RESPONSE_BYTES) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("Zabbix response exceeds byte budget"));
                return;
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
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
