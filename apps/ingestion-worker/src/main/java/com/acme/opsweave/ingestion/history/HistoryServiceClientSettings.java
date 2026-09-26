package com.acme.opsweave.ingestion.history;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("opsweave.history.service")
public record HistoryServiceClientSettings(String tokenUri, String clientId, String clientSecret, boolean loopbackTest) {
    public void validate() {
        try {
            var uri = URI.create(tokenUri);
            boolean transport = loopbackTest ? "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "[::1]").contains(uri.getHost()) && uri.getPort() > 0 : "https".equals(uri.getScheme());
            if (!transport || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawPath().isEmpty() || tokenUri.length() > 2048 || !uri.normalize().equals(uri)
                || clientId == null || !clientId.matches("[A-Za-z0-9._-]{1,128}") || clientSecret == null || clientSecret.length() < 16 || clientSecret.length() > 4096
                || clientSecret.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid explicit history service credential configuration"); }
    }
    @Override public String toString() { return "HistoryServiceClientSettings[redacted]"; }
}
