package com.acme.opsweave.catalog.domain;

import java.util.Optional;
import java.util.function.Function;

public final class CatalogRules {
    private CatalogRules() {}
    public static void editable(ModelDefinition definition, int expected) {
        if (definition.builtin() || expected < 0 || expected >= 1000000) throw new IllegalArgumentException("Invalid model draft");
    }
    public static void publication(ModelDefinition definition, Optional<ModelDefinition> latest, Function<ModelDefinition.Ref, Optional<ModelDefinition>> resolve) {
        if (latest.isPresent()) definition.requireCompatibleWith(latest.get());
        else if (definition.revision() != 1) throw new CatalogFailure(CatalogFailure.Code.INCOMPATIBLE_REVISION);
        if (definition.endpoints() != null) for (var ref : new ModelDefinition.Ref[]{definition.endpoints().from(), definition.endpoints().to()}) {
            var endpoint = resolve.apply(ref).orElseThrow(() -> new CatalogFailure(CatalogFailure.Code.UNKNOWN_ENTITY_TYPE));
            if (endpoint.kind() != ModelDefinition.Kind.ENTITY) throw new CatalogFailure(CatalogFailure.Code.UNKNOWN_ENTITY_TYPE);
        }
    }
}
