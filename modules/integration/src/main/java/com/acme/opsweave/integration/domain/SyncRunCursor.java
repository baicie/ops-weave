package com.acme.opsweave.integration.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Position in the newest-first scan run list. The value is opaque to callers and carries no
 * authority: every read still resolves the tenant from the trusted Principal and the source
 * from configuration. The stored instant keeps its full precision so a page boundary cannot
 * drift past a row that shares the same millisecond.
 */
public record SyncRunCursor(Instant startedAt, UUID id) {
    private static final int MAX_ENCODED = 192;

    public SyncRunCursor {
        if (startedAt == null || id == null) {
            throw new IllegalArgumentException("Invalid scan cursor");
        }
    }

    public String encode() {
        String text = startedAt.toString() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public static SyncRunCursor decode(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_ENCODED) {
            throw new IllegalArgumentException("Invalid scan cursor");
        }
        try {
            String text = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int split = text.lastIndexOf(':');
            if (split < 1) {
                throw new IllegalArgumentException("Invalid scan cursor");
            }
            return new SyncRunCursor(
                Instant.parse(text.substring(0, split)),
                UUID.fromString(text.substring(split + 1))
            );
        } catch (RuntimeException failed) {
            throw new IllegalArgumentException("Invalid scan cursor");
        }
    }
}
