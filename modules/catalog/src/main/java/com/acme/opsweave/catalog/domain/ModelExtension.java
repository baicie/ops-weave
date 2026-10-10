package com.acme.opsweave.catalog.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Tenant-namespaced extension overlay for one fixed base model version. Extensions only add
 * optional bounded scalar fields on top of the immutable base body: base fields, system metadata
 * and base semantics can never be overridden, and an extension is not a new model identity.
 * Wire format is owned by contracts/schemas/v1/model-extension*.schema.json.
 */
public record ModelExtension(ModelDefinition.Ref base, int revision, List<ModelDefinition.Field> fields) {
    public static final String PROFILE = "safe-scalars-v1";
    public static final int MAX_FIELDS = 32;

    public ModelExtension {
        Objects.requireNonNull(base, "base");
        if (revision < 1 || revision > 10000) invalid();
        fields = List.copyOf(fields);
        if (fields.isEmpty() || fields.size() > MAX_FIELDS) invalid();
        if (fields.stream().map(ModelDefinition.Field::id).distinct().count() != fields.size()) invalid();
        // Additive optional fields only; required fields would break records written earlier.
        for (ModelDefinition.Field field : fields) if (field.required()) invalid();
    }

    /**
     * The base model body is immutable. Extension fields may never collide with base field
     * identities and the combined field budget stays bounded.
     */
    public void requireCompatibleBase(ModelDefinition baseDefinition) {
        if (baseDefinition == null || !base.equals(baseDefinition.ref()) || baseDefinition.kind() != ModelDefinition.Kind.ENTITY) {
            throw new IllegalArgumentException("Invalid extension base");
        }
        for (ModelDefinition.Field field : fields) {
            if (baseDefinition.fields().stream().anyMatch(baseField -> baseField.id().equals(field.id()))) {
                throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
            }
        }
        if (baseDefinition.fields().size() + fields.size() > ModelDefinition.MAX_FIELDS_LIMIT) invalid();
    }

    /** Validation-only view combining the immutable base body with these optional tenant fields. */
    public ModelDefinition appliedTo(ModelDefinition baseDefinition) {
        requireCompatibleBase(baseDefinition);
        var combined = new ArrayList<>(baseDefinition.fields());
        combined.addAll(fields);
        return new ModelDefinition(base.id(), base.revision(), ModelDefinition.Kind.ENTITY,
            baseDefinition.label(), baseDefinition.description(), combined, null);
    }

    /** Additive consecutive revisions only; earlier field semantics stay pinned. */
    public void requireCompatibleWith(ModelExtension previous) {
        if (!base.equals(previous.base) || revision != previous.revision + 1) throw new CatalogFailure(CatalogFailure.Code.INCOMPATIBLE_REVISION);
        for (ModelDefinition.Field old : previous.fields) {
            ModelDefinition.Field next = fields.stream().filter(field -> field.id().equals(old.id())).findFirst()
                .orElseThrow(() -> new CatalogFailure(CatalogFailure.Code.INCOMPATIBLE_REVISION));
            if (old.type() != next.type() || old.required() != next.required() || !Objects.equals(old.maxLength(), next.maxLength())
                || !numericEqual(old.min(), next.min()) || !numericEqual(old.max(), next.max())
                || !old.choices().equals(next.choices())) throw new CatalogFailure(CatalogFailure.Code.INCOMPATIBLE_REVISION);
        }
    }

    /** Length-prefixed UTF-8 components, fields sorted by id, canonical decimal bounds. */
    public String digest() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            var parts = new ArrayList<>(List.of("opsweave-model-extension-v1", base.id(),
                Integer.toString(base.revision()), Integer.toString(revision), PROFILE));
            for (ModelDefinition.Field field : fields.stream().sorted(Comparator.comparing(ModelDefinition.Field::id)).toList()) {
                parts.addAll(List.of(field.id(), field.label(), field.type().name(), Boolean.toString(field.required()),
                    Objects.toString(field.maxLength(), ""), decimal(field.min()), decimal(field.max()),
                    Integer.toString(field.choices().size())));
                parts.addAll(field.choices());
            }
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                md.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                md.update(bytes);
            }
            return "sha256:" + HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static boolean numericEqual(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static String decimal(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }

    private static void invalid() { throw new IllegalArgumentException("Invalid model extension"); }
}
