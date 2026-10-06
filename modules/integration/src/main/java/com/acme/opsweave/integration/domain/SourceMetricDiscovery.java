package com.acme.opsweave.integration.domain;

import java.math.BigInteger;
import java.util.*;

/** Bounded source metadata. No values, history, scripts or binding mutations. */
public record SourceMetricDiscovery(List<Item> items, boolean complete, String scanConsistency,
        String statusCode, String fingerprint) {
    public static final int LIMIT = 20;
    public SourceMetricDiscovery {
        items = List.copyOf(items);
        WorkflowDefinition.checkDigest(fingerprint);
        if (items.size() > LIMIT || !Set.of("FIRST_PAGE_MATCH", "LABELED_FIXTURE", "UNVERIFIED").contains(scanConsistency)
            || !Set.of("READ_VERIFIED", "INCOMPLETE", "UNREACHABLE").contains(statusCode)
            || complete && (scanConsistency.equals("UNVERIFIED") || !statusCode.equals("READ_VERIFIED"))
            || statusCode.equals("READ_VERIFIED") != complete
            || statusCode.equals("UNREACHABLE") && (!items.isEmpty() || complete || !scanConsistency.equals("UNVERIFIED"))
            || !fingerprint.equals(digest(items))) throw new IllegalArgumentException("Invalid metric discovery");
        BigInteger previous = BigInteger.ZERO;
        for (var item : items) {
            var id = new BigInteger(item.itemId());
            if (id.compareTo(previous) <= 0) throw new IllegalArgumentException("Unordered metric discovery");
            previous = id;
        }
    }
    public String scope() { return "FIRST_ITEM_PAGE"; }
    public int limit() { return LIMIT; }
    public record Mapping(String id, int revision, String digest, String metricKey, String unit,
            String valueType, String valueTransform) {
        public Mapping {
            label(id, 96, false); label(metricKey, 128, false); label(unit, 64, false); label(valueTransform, 96, false);
            WorkflowDefinition.checkDigest(digest);
            if (revision < 1 || !Set.of("DOUBLE", "INTEGER", "STRING").contains(valueType)) throw new IllegalArgumentException("Invalid metric mapping");
        }
        public static Mapping from(MappingDefinition d) {
            var pin=d.pin();
            return new Mapping(d.id(), d.mappingRevision(), pin.digest(), d.metricKey(), d.unit(), d.valueType().name(), d.valueTransform());
        }
    }
    public record Item(String itemId, String hostId, String sourceKey, String name, String sourceUnit,
            String sourceValueType, String mappingStatus, Mapping mapping) {
        public Item {
            numericId(itemId); numericId(hostId); label(sourceKey, 2048, false); label(name, 512, false); label(sourceUnit, 64, true);
            if (!Set.of("FLOAT", "UNSIGNED", "CHARACTER", "LOG", "TEXT", "BINARY", "UNKNOWN").contains(sourceValueType)
                || !Set.of("MAPPED", "NO_MAPPING", "TYPE_MISMATCH").contains(mappingStatus)
                || mappingStatus.equals("NO_MAPPING") != (mapping == null)
                || mapping != null && mappingStatus.equals("MAPPED") != compatible(sourceValueType, mapping.valueType()))
                throw new IllegalArgumentException("Invalid discovered metric");
        }
    }
    public static boolean compatible(String source, String target) {
        return Set.of("FLOAT", "UNSIGNED").contains(source) ? Set.of("DOUBLE", "INTEGER").contains(target)
            : Set.of("CHARACTER", "LOG", "TEXT").contains(source) && target.equals("STRING");
    }
    public static void numericId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Invalid source metadata ID");
    }
    private static void label(String value, int max, boolean empty) {
        if (value == null || value.length() > max || !empty && value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid source metadata label");
    }
    public static String digest(List<Item> items) {
        var parts = new ArrayList<String>(); parts.add("source-metric-metadata-v1");
        for (var i : items) {
            parts.addAll(List.of(i.itemId(), i.hostId(), i.sourceKey(), i.name(), i.sourceUnit(), i.sourceValueType(), i.mappingStatus()));
            if (i.mapping() == null) parts.add("absent");
            else { var m = i.mapping(); parts.addAll(List.of("present", m.id(), Integer.toString(m.revision()), m.digest(), m.metricKey(), m.unit(), m.valueType(), m.valueTransform())); }
        }
        return WorkflowDefinition.hash(parts);
    }
    public static SourceMetricDiscovery unavailable() { return new SourceMetricDiscovery(List.of(), false, "UNVERIFIED", "UNREACHABLE", digest(List.of())); }
}
