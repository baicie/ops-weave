package com.acme.opsweave.catalog.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Framework-free model semantics; wire format is owned by contracts/model-catalog.md. */
public record ModelDefinition(String id, int revision, Kind kind, String label, String description,
                              List<Field> fields, Endpoints endpoints) {
    public static final String PROFILE = "safe-scalars-v1";
    public enum Kind { ENTITY, RELATION }
    public enum Type { TEXT, INTEGER, DECIMAL, BOOLEAN, ENUM, DATETIME }
    public enum Cardinality { ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY }
    private static final Set<String> RESERVED = Set.of("id", "tenant_id", "tenantId", "version", "source", "source_ref", "created_at", "updated_at", "__proto__", "constructor", "prototype");
    public static final BigDecimal MAX_NUMBER = new BigDecimal("9007199254740991");
    public record Ref(String id, int revision) {
        public Ref { checkId(id); if (revision < 1 || revision > 10000) invalid(); }
    }
    public record Endpoints(Ref from, Ref to, Cardinality cardinality) {
        public Endpoints { Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(cardinality); }
    }
    public record Field(String id, String label, Type type, boolean required, Integer maxLength,
                        BigDecimal min, BigDecimal max, List<String> choices) {
        public Field {
            if (id == null || !id.matches("[a-z][a-z0-9_]{0,47}") || RESERVED.contains(id)) invalid();
            text(label, 80); Objects.requireNonNull(type);
            choices = choices == null ? List.of() : List.copyOf(choices);
            boolean text = type == Type.TEXT || type == Type.ENUM;
            if (text != (maxLength != null) || maxLength != null && (maxLength < 1 || maxLength > 2048)) invalid();
            if (type == Type.ENUM) {
                if (choices.isEmpty() || choices.size() > 32 || new HashSet<>(choices).size() != choices.size()) invalid();
                for (String choice : choices) { text(choice, 80); if (!choice.equals(choice.strip()) || choice.length() > maxLength) invalid(); }
            } else if (!choices.isEmpty()) invalid();
            if (type != Type.INTEGER && type != Type.DECIMAL && (min != null || max != null)) invalid();
            for (BigDecimal bound : new BigDecimal[]{min, max}) if (bound != null && (bound.abs().compareTo(MAX_NUMBER) > 0 || type == Type.INTEGER && bound.stripTrailingZeros().scale() > 0)) invalid();
            if (min != null && max != null && min.compareTo(max) > 0) invalid();
        }
    }
    public ModelDefinition {
        checkId(id); if (revision < 1 || revision > 10000) invalid();
        Objects.requireNonNull(kind); text(label, 80);
        if (description == null || description.length() > 1000) invalid();
        fields = List.copyOf(fields);
        if (fields.size() > 32 || fields.stream().map(Field::id).distinct().count() != fields.size()) invalid();
        if (kind == Kind.ENTITY && endpoints != null || kind == Kind.RELATION && (endpoints == null || !fields.isEmpty())) invalid();
    }
    public Ref ref() { return new Ref(id, revision); }
    public boolean builtin() { return id.startsWith("builtin."); }
    public static void checkId(String id) { if (id == null || !id.matches("(builtin|custom)\\.[a-z][a-z0-9_]{0,47}")) invalid(); }
    private static void text(String text, int max) { if (text == null || text.isBlank() || text.length() > max) invalid(); }
    private static void invalid() { throw new IllegalArgumentException("Invalid model definition"); }

    /** Additive revisions only until an explicit data migration workflow exists. */
    public void requireCompatibleWith(ModelDefinition previous) {
        if (!id.equals(previous.id) || revision != previous.revision + 1 || kind != previous.kind || !Objects.equals(endpoints, previous.endpoints)) throw new CatalogFailure(CatalogFailure.Code.INCOMPATIBLE_REVISION);
        for (Field old : previous.fields) {
            Field next = fields.stream().filter(f -> f.id.equals(old.id)).findFirst().orElseThrow(() -> new CatalogFailure(CatalogFailure.Code.INCOMPATIBLE_REVISION));
            // Cosmetic labels may change; scalar meaning, requiredness and validation are pinned.
            if (old.type != next.type || old.required != next.required || !Objects.equals(old.maxLength, next.maxLength)
                || !numericEqual(old.min, next.min) || !numericEqual(old.max, next.max) || !old.choices.equals(next.choices)) throw new CatalogFailure(CatalogFailure.Code.INCOMPATIBLE_REVISION);
        }
        for (Field next : fields) if (next.required && previous.fields.stream().noneMatch(f -> f.id.equals(next.id))) throw new CatalogFailure(CatalogFailure.Code.INCOMPATIBLE_REVISION);
    }
    private static boolean numericEqual(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }

    /** Length-prefixed UTF-8 components, fields sorted by id, canonical decimal bounds. */
    public String digest() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            var parts = new ArrayList<String>(List.of("opsweave-model-v1", id, Integer.toString(revision), kind.name(), label, description, PROFILE));
            for (Field f : fields.stream().sorted(Comparator.comparing(Field::id)).toList()) {
                parts.addAll(List.of(f.id, f.label, f.type.name(), Boolean.toString(f.required), Objects.toString(f.maxLength, ""), decimal(f.min), decimal(f.max), Integer.toString(f.choices.size())));
                parts.addAll(f.choices);
            }
            if (endpoints != null) parts.addAll(List.of(endpoints.from.id, Integer.toString(endpoints.from.revision), endpoints.to.id, Integer.toString(endpoints.to.revision), endpoints.cardinality.name()));
            for (String part : parts) { byte[] bytes = part.getBytes(StandardCharsets.UTF_8); md.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); md.update(bytes); }
            return "sha256:" + HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String decimal(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
}
