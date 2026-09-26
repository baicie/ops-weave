package com.acme.opsweave.ingestion.history;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import tools.jackson.databind.json.JsonMapper;

/** Explicit client_credentials profile, one request, bounded response, in-memory monotonic expiry. */
public final class ClientCredentialsAuthorization implements HistoryAuthorization {
    private final LoopbackHttp http;
    private final String tokenPath, basic, checkpointIdentity;
    private final LongSupplier nanoTime;
    private String token;
    private long expires;
    public ClientCredentialsAuthorization(HistoryServiceClientSettings settings) { this(settings, System::nanoTime); }
    ClientCredentialsAuthorization(HistoryServiceClientSettings settings, LongSupplier nanoTime) {
        settings.validate(); var uri = URI.create(settings.tokenUri()); this.nanoTime = nanoTime;
        checkpointIdentity = settings.tokenUri() + "#" + settings.clientId();
        http = new LoopbackHttp(URI.create(uri.getScheme() + "://" + uri.getRawAuthority()), settings.loopbackTest()); tokenPath = uri.getRawPath();
        String credentials = URLEncoder.encode(settings.clientId(), StandardCharsets.UTF_8) + ":" + URLEncoder.encode(settings.clientSecret(), StandardCharsets.UTF_8);
        basic = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
    @Override public synchronized String authorization() {
        if (token != null && nanoTime.getAsLong() - expires < 0) return "Bearer " + token;
        token = null;
        try {
            var response = http.request("POST", tokenPath, "grant_type=client_credentials&scope=opsweave.history.read",
                Map.of("Authorization", basic, "Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"), 32768, Duration.ofSeconds(5));
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").matches("(?i)application/json(?:\\s*;.*)?")) throw new IllegalArgumentException();
            var node = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(response.body());
            if (!node.isObject() || !node.propertyNames().containsAll(Set.of("access_token", "token_type", "expires_in"))
                || !Set.of("access_token", "token_type", "expires_in", "scope").containsAll(node.propertyNames())
                || !node.get("access_token").isString() || !node.get("token_type").isString() || !node.get("token_type").asString().equalsIgnoreCase("Bearer")
                || !node.get("expires_in").isIntegralNumber() || !node.get("expires_in").canConvertToInt()
                || (node.has("scope") && (!node.get("scope").isString() || !node.get("scope").asString().equals("opsweave.history.read")))) throw new IllegalArgumentException();
            var candidate = node.get("access_token").asString(); int seconds = node.get("expires_in").asInt();
            if (candidate.length() > 8192 || !candidate.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+") || seconds < 1 || seconds > 900) throw new IllegalArgumentException();
            token = candidate; expires = nanoTime.getAsLong() + Duration.ofSeconds(Math.max(0, seconds - 5)).toNanos(); return "Bearer " + token;
        } catch (RuntimeException failed) { token = null; throw new IllegalStateException("HISTORY_SERVICE_TOKEN_UNAVAILABLE"); }
    }
    @Override public synchronized void unauthorized() { token = null; expires = 0; }
    @Override public String checkpointIdentity() { return checkpointIdentity; }
}
