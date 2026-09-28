package com.acme.opsweave.catalog.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** One bounded in-memory sample. No persistence, default invention or executable expressions. */
public record ModelPreview(boolean valid, Map<String, Object> values, List<Issue> issues, List<Change> changes) {
    public record Issue(String field, String code) {}
    public record Change(String field, String rule) {}
    public ModelPreview { values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); issues = List.copyOf(issues); changes = List.copyOf(changes); }
    public static ModelPreview evaluate(ModelDefinition model, Map<String, Object> sample) {
        if (model.kind() != ModelDefinition.Kind.ENTITY || sample.size() > 32) throw new IllegalArgumentException("Invalid preview sample");
        var values = new LinkedHashMap<String, Object>(); var issues = new ArrayList<Issue>(); var changes = new ArrayList<Change>();
        for (var entry : sample.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 80) throw new IllegalArgumentException("Invalid sample field");
            Object v = entry.getValue();
            if (v != null && !(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean) || v instanceof String s && s.length() > 2048) throw new IllegalArgumentException("Invalid sample value");
            if (model.fields().stream().noneMatch(f -> f.id().equals(entry.getKey()))) issues.add(new Issue(entry.getKey(), "UNKNOWN_FIELD"));
        }
        for (var f : model.fields()) {
            if (!sample.containsKey(f.id())) { if (f.required()) issues.add(new Issue(f.id(), "MISSING_REQUIRED")); continue; }
            Object raw = sample.get(f.id());
            if (raw == null) { values.put(f.id(), null); if (f.required()) issues.add(new Issue(f.id(), "NULL_REQUIRED")); continue; }
            try {
                Object value = convert(f, raw);
                if (!raw.equals(value)) changes.add(new Change(f.id(), raw instanceof String && (f.type() == ModelDefinition.Type.TEXT || f.type() == ModelDefinition.Type.ENUM) ? "trim-text" : "strict-scalar-conversion"));
                if (value instanceof String s && f.maxLength() != null && s.length() > f.maxLength()) throw new Invalid("TOO_LONG");
                if (value instanceof String s && s.isEmpty() && f.required()) throw new Invalid("EMPTY_REQUIRED");
                if (f.type() == ModelDefinition.Type.ENUM && !f.choices().contains(value)) throw new Invalid("ENUM_MISMATCH");
                if (value instanceof BigDecimal n && (f.min() != null && n.compareTo(f.min()) < 0 || f.max() != null && n.compareTo(f.max()) > 0)) throw new Invalid("OUT_OF_RANGE");
                values.put(f.id(), value);
            } catch (Invalid invalid) { issues.add(new Issue(f.id(), invalid.code)); }
        }
        return new ModelPreview(issues.isEmpty(), values, issues, changes);
    }
    private static Object convert(ModelDefinition.Field f, Object raw) {
        try {
            return switch (f.type()) {
                case TEXT, ENUM -> { if (!(raw instanceof String s)) throw new Invalid("TYPE_MISMATCH"); yield s.strip(); }
                case BOOLEAN -> { if (raw instanceof Boolean) yield raw; if (raw instanceof String s && (s.strip().equals("true") || s.strip().equals("false"))) yield Boolean.valueOf(s.strip()); throw new Invalid("TYPE_MISMATCH"); }
                case INTEGER, DECIMAL -> {
                    if (!(raw instanceof Number) && !(raw instanceof String)) throw new Invalid("TYPE_MISMATCH");
                    String text = raw.toString().strip();
                    if (raw instanceof String && !text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw new Invalid("TYPE_MISMATCH");
                    BigDecimal value = new BigDecimal(text).stripTrailingZeros();
                    if (value.abs().compareTo(ModelDefinition.MAX_NUMBER) > 0 || value.scale() > 12) throw new Invalid("NUMERIC_LIMIT");
                    if (f.type() == ModelDefinition.Type.INTEGER && value.scale() > 0) throw new Invalid("TYPE_MISMATCH");
                    yield value;
                }
                case DATETIME -> { if (!(raw instanceof String s) || s.length() > 80) throw new Invalid("TYPE_MISMATCH"); yield OffsetDateTime.parse(s.strip()).toInstant().toString(); }
            };
        } catch (java.time.DateTimeException | NumberFormatException invalid) { throw new Invalid("TYPE_MISMATCH"); }
    }
    private static final class Invalid extends RuntimeException { final String code; Invalid(String code) { this.code = code; } }
}
