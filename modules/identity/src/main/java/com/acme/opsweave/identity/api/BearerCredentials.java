package com.acme.opsweave.identity.api;

import java.util.Objects;

public record BearerCredentials(String token) {
    public BearerCredentials {
        Objects.requireNonNull(token, "token");
        if (token.isBlank() || token.length() > 4096) {
            throw new IllegalArgumentException("Invalid bearer token");
        }
    }

    @Override
    public String toString() {
        return "BearerCredentials[redacted]";
    }
}
