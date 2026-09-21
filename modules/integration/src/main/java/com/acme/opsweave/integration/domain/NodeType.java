package com.acme.opsweave.integration.domain;

import java.util.Locale;
import java.util.Objects;

public enum NodeType {
    SOURCE("Source"),
    PARSE("Parse"),
    MAP("Map"),
    VALIDATE("Validate"),
    ENTITY_RESOLVE("EntityResolve"),
    WRITE_OBSERVATION("WriteObservation");

    private final String wireName;

    NodeType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static NodeType fromWire(String value) {
        Objects.requireNonNull(value, "nodeType");
        for (NodeType type : values()) {
            if (type.wireName.equals(value) || type.name().equalsIgnoreCase(value.replace("-", "_"))) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported pipeline node: " + value.toLowerCase(Locale.ROOT));
    }
}
