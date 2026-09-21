package com.acme.opsweave.identity.domain;

import java.util.Objects;

public record SubjectId(String value) {
    public SubjectId {
        Objects.requireNonNull(value, "subjectId");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid subjectId");
        }
    }
}
