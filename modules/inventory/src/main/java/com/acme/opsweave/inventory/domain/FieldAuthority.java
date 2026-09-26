package com.acme.opsweave.inventory.domain;

import java.util.*;

/** Projects approved fields over the latest primary snapshot, never over a previous projection. */
public final class FieldAuthority {
    private FieldAuthority() {}
    public static Map<String,String> fields(Entity primary) {
        var result = new TreeMap<String,String>(); result.put("name", primary.name());
        for (String field : SourceReview.FIELDS) if (!field.equals("name") && primary.attributes().get(field) instanceof String value && !value.isBlank()) result.put(field, value);
        return SourceReview.fields(result, true);
    }
    public static Entity project(Entity primary, SourceReview active, long version) {
        var attrs = new HashMap<>(primary.attributes()); attrs.remove("fieldAuthority");
        String name = primary.name();
        if (active != null) {
            if (active.status() != SourceReview.Status.ACCEPTED || !active.tenantId().equals(primary.tenantId()) || !active.entityId().equals(primary.id())) throw new IllegalStateException("Invalid field authority scope");
            var selected = new TreeSet<String>();
            for (var choice : active.decisions().getFirst().choices().entrySet()) if (choice.getValue() == SourceReview.Choice.SUPPLEMENTAL) {
                selected.add(choice.getKey());
                if (choice.getKey().equals("name")) name = active.values().get("name"); else attrs.put(choice.getKey(), active.values().get(choice.getKey()));
            }
            attrs.put("fieldAuthority", Map.of("reviewId", active.id().toString(), "sourceInstanceId", active.source().sourceInstanceId(),
                "observedAt", active.observedAt().toString(), "ingestedAt", active.ingestedAt().toString(), "expiresAt", active.expiresAt().toString(),
                "mappingDigest", active.mappingDigest(), "fields", List.copyOf(selected), "dataMode", "import"));
        }
        EntityReadLimits.check(attrs);
        return new Entity(primary.id(), primary.tenantId(), primary.entityType(), name, primary.lifecycle(), version, primary.lastSeen(), attrs);
    }
    public static Entity inactive(Entity primary) { return new Entity(primary.id(), primary.tenantId(), primary.entityType(), primary.name(), Lifecycle.INACTIVE, primary.version(), primary.lastSeen(), primary.attributes()); }
    public static Observation observation(SourceReview review) {
        var fields = new HashMap<String,Object>(review.values());
        if (fields.containsKey("name")) fields.put("entityName", fields.remove("name"));
        fields.put("dataMode", "import"); fields.put("mappingDigest", review.mappingDigest());
        fields.put("presence", "not-asserted");
        return new Observation("source-review:" + review.id(), review.source(), review.entityId(), review.observedAt(), review.ingestedAt(),
            fields, "source-review:" + review.id(), 1);
    }
}
