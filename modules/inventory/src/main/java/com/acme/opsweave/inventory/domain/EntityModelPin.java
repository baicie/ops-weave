package com.acme.opsweave.inventory.domain;

import java.util.Objects;

/** Immutable reference to the published schema used to validate an entity instance. */
public record EntityModelPin(String id, int revision, String digest) {
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("^(builtin|custom)\\.[a-z][a-z0-9_]{0,47}$");
    private static final java.util.regex.Pattern DIGEST = java.util.regex.Pattern.compile("^sha256:[a-f0-9]{64}$");

    public EntityModelPin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(digest, "digest");
        if (!ID.matcher(id).matches() || revision < 1 || revision > 10000 || !DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("Invalid entity model pin");
        }
    }
}
