package com.acme.opsweave.platform.identity;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("opsweave.auth.history-service")
public record HistoryServiceSettings(boolean enabled, String issuer, String jwkSetUri, String grantsFile, boolean loopbackTest) {
    public static final String AUDIENCE = "opsweave-history", SCOPE = "opsweave.history.read";
    public void validate(String bindAddress) {
        for (String value : new String[]{issuer, jwkSetUri}) {
            try {
                var uri = URI.create(value);
                boolean transport = loopbackTest ? "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "[::1]").contains(uri.getHost()) && uri.getPort() > 0
                    : "https".equals(uri.getScheme());
                if (!transport || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || value.length() > 2048 || !uri.normalize().equals(uri)) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid explicit history service identity endpoints"); }
        }
        if (grantsFile == null || !Path.of(grantsFile).isAbsolute() || (loopbackTest && !Set.of("127.0.0.1", "::1").contains(bindAddress)))
            throw new IllegalStateException("Invalid explicit history service identity configuration");
    }
}
