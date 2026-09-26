package com.acme.opsweave.platform.identity;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("opsweave.auth.oidc")
public record OidcSettings(String issuer, String authorizationUri, String tokenUri, String jwkSetUri,
        String clientId, String clientSecret, String publicOrigin, String grantsFile, boolean loopbackTest, int sessionSeconds) {
    public void validate(String bindAddress) {
        for (String value : new String[]{issuer, authorizationUri, tokenUri, jwkSetUri, publicOrigin}) endpoint(value);
        URI origin = URI.create(publicOrigin);
        if (!origin.getRawPath().isEmpty() || clientId == null || !clientId.matches("[A-Za-z0-9._-]{1,128}")
            || clientSecret == null || clientSecret.length() < 16 || clientSecret.length() > 4096 || clientSecret.chars().anyMatch(Character::isISOControl)
            || grantsFile == null || !Path.of(grantsFile).isAbsolute() || sessionSeconds < 60 || sessionSeconds > 1800
            || (loopbackTest && !Set.of("127.0.0.1", "::1").contains(bindAddress))) throw new IllegalStateException("Invalid explicit OIDC configuration");
    }
    private void endpoint(String value) {
        try {
            var uri = URI.create(value);
            boolean transport = loopbackTest ? "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "[::1]").contains(uri.getHost()) && uri.getPort() > 0
                : "https".equals(uri.getScheme());
            if (!transport || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null
                || value.length() > 2048 || !uri.normalize().equals(uri)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalStateException("OIDC requires explicit HTTPS endpoints (or explicit loopback protocol test)"); }
    }
    public String callback() { return publicOrigin + "/api/v1/auth/callback"; }
    public String cookieName() { return loopbackTest ? "opsweave-test-session" : "__Host-opsweave"; }
    @Override public String toString() { return "OidcSettings[redacted]"; }
}
