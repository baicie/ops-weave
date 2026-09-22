package com.acme.opsweave.integration.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lookup of mapping documents by connector and exact source key. */
public final class MappingRegistry {
    private final Map<String, MappingDefinition> bySourceKey;

    public MappingRegistry(List<MappingDefinition> definitions) {
        Map<String, MappingDefinition> loaded = new LinkedHashMap<>();
        for (MappingDefinition definition : definitions) {
            String key = definition.connector() + "\0" + definition.itemKeyExact();
            if (loaded.putIfAbsent(key, definition) != null) {
                throw new IllegalArgumentException("Duplicate mapping for one source key");
            }
        }
        this.bySourceKey = Map.copyOf(loaded);
    }

    public Optional<MappingDefinition> find(String connector, String itemKey) {
        if (connector == null || itemKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bySourceKey.get(connector + "\0" + itemKey));
    }

    public boolean isEmpty() {
        return bySourceKey.isEmpty();
    }
}
