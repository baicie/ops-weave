package com.acme.opsweave.integration.domain;

import java.util.Locale;
import java.util.Objects;

public enum ErrorPolicy {
    FAIL_FAST,
    SKIP_RECORD;

    public static ErrorPolicy fromWire(String value) {
        Objects.requireNonNull(value, "errorPolicy");
        String normalized = value.trim();
        if ("failFast".equals(normalized) || "FAIL_FAST".equalsIgnoreCase(normalized)) {
            return FAIL_FAST;
        }
        if ("skipRecord".equals(normalized) || "SKIP_RECORD".equalsIgnoreCase(normalized)) {
            return SKIP_RECORD;
        }
        throw new IllegalArgumentException("Unknown errorPolicy: " + value.toLowerCase(Locale.ROOT));
    }

    public String wireName() {
        return this == FAIL_FAST ? "failFast" : "skipRecord";
    }
}
