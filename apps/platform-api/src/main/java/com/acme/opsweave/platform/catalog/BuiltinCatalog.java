package com.acme.opsweave.platform.catalog;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class BuiltinCatalog {
    private final JsonNode bundle;
    private final List<ModelDefinition> definitions;
    public BuiltinCatalog() {
        try (var input = BuiltinCatalog.class.getClassLoader().getResourceAsStream("catalog/opsweave-core-1.0.0.json")) {
            if (input == null) throw new IllegalStateException("Missing built-in catalog");
            bundle = CatalogJson.JSON.readTree(input.readAllBytes());
            var values = new ArrayList<ModelDefinition>(); for (var n : bundle.get("definitions")) values.add(CatalogJson.definition(n));
            definitions = List.copyOf(values);
            if (definitions.stream().map(ModelDefinition::ref).distinct().count() != definitions.size()) throw new IllegalStateException("Duplicate built-in model");
        } catch (java.io.IOException | IllegalArgumentException failed) { throw new IllegalStateException("Invalid built-in catalog"); }
    }
    public List<ModelDefinition> definitions() { return definitions; }
    public JsonNode bundle() { return bundle.deepCopy(); }
}
