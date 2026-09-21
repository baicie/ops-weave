package com.acme.opsweave.identity.domain;

import java.util.Objects;

public record AuthorizationDecision(boolean allowed, String reasonCode) {
    public AuthorizationDecision {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank() || reasonCode.length() > 64) {
            throw new IllegalArgumentException("Invalid reasonCode");
        }
        if (allowed && !"ALLOW".equals(reasonCode)) {
            throw new IllegalArgumentException("Allowed decision must use ALLOW");
        }
        if (!allowed && "ALLOW".equals(reasonCode)) {
            throw new IllegalArgumentException("Denied decision cannot use ALLOW");
        }
    }

    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(true, "ALLOW");
    }

    public static AuthorizationDecision deny(String reasonCode) {
        return new AuthorizationDecision(false, reasonCode);
    }

    public boolean denied() {
        return !allowed;
    }
}
