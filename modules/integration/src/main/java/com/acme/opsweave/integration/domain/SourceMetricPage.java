package com.acme.opsweave.integration.domain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** A server-owned membership manifest and one bounded metadata page. Never source values. */
public record SourceMetricPage(Manifest manifest, int offset, List<SourceMetricDiscovery.Item> items,
        String statusCode, String scanConsistency, String fingerprint) {
    public static final int CAPACITY = 1000, LIMIT = 20;
    public record Manifest(UUID snapshotId, Instant asOf, Instant expiresAt, List<String> itemIds, String fingerprint) {
        public Manifest {
            Objects.requireNonNull(snapshotId); Objects.requireNonNull(asOf); Objects.requireNonNull(expiresAt);
            itemIds = List.copyOf(itemIds); WorkflowDefinition.checkDigest(fingerprint);
            if (itemIds.size() > CAPACITY || !expiresAt.equals(asOf.plus(SourceInspection.VALID_FOR))
                || !fingerprint.equals(membershipDigest(itemIds))) throw new IllegalArgumentException("Invalid metric membership");
            BigInteger previous = BigInteger.ZERO;
            for (var id : itemIds) { SourceMetricDiscovery.numericId(id); var n = new BigInteger(id);
                if (n.compareTo(previous) <= 0) throw new IllegalArgumentException("Unordered metric membership"); previous = n; }
        }
        public int total() { return itemIds.size(); }
        public static Manifest capture(UUID id, Instant at, List<String> ids) {
            return new Manifest(id, at, at.plus(SourceInspection.VALID_FOR), ids, membershipDigest(ids));
        }
    }
    public SourceMetricPage {
        items = List.copyOf(items); WorkflowDefinition.checkDigest(fingerprint);
        if (offset < 0 || offset >= CAPACITY || offset % LIMIT != 0 || items.size() > LIMIT
            || !Set.of("READ_VERIFIED", "MEMBERSHIP_CHANGED", "CAPACITY", "UNREACHABLE").contains(statusCode)
            || !Set.of("ITEMID_WATERMARK", "LABELED_FIXTURE", "UNVERIFIED").contains(scanConsistency)
            || !fingerprint.equals(pageDigest(manifest, offset, items, statusCode, scanConsistency)))
            throw new IllegalArgumentException("Invalid metric page");
        if (statusCode.equals("READ_VERIFIED")) {
            if (manifest == null || scanConsistency.equals("UNVERIFIED") || offset > manifest.total()
                || offset == manifest.total() && offset != 0
                || !items.stream().map(SourceMetricDiscovery.Item::itemId).toList()
                    .equals(manifest.itemIds().subList(offset, Math.min(offset + LIMIT, manifest.total()))))
                throw new IllegalArgumentException("Metric page does not cover selected membership");
        } else if (!items.isEmpty() || !scanConsistency.equals("UNVERIFIED") || manifest == null && offset != 0)
            throw new IllegalArgumentException("Failed discovery cannot carry metadata");
    }
    public boolean verified() { return statusCode.equals("READ_VERIFIED"); }
    public boolean complete() { return verified() && offset + items.size() == manifest.total(); }
    public Integer nextOffset() { return verified() && !complete() ? offset + LIMIT : null; }
    public static SourceMetricPage verified(Manifest m, int offset, List<SourceMetricDiscovery.Item> items, String consistency) {
        return new SourceMetricPage(m, offset, items, "READ_VERIFIED", consistency, pageDigest(m, offset, items, "READ_VERIFIED", consistency));
    }
    public static SourceMetricPage failed(Manifest m, int offset, String code) {
        return new SourceMetricPage(m, offset, List.of(), code, "UNVERIFIED", pageDigest(m, offset, List.of(), code, "UNVERIFIED"));
    }
    public static String membershipDigest(List<String> ids) {
        var parts = new ArrayList<>(List.of("source-metric-membership-v1", Integer.toString(ids.size()))); parts.addAll(ids); return WorkflowDefinition.hash(parts);
    }
    public static String pageDigest(Manifest m, int offset, List<SourceMetricDiscovery.Item> items, String code, String consistency) {
        var parts = new ArrayList<>(List.of("source-metric-page-v1"));
        if (m == null) parts.add("absent"); else parts.addAll(List.of("present", m.snapshotId().toString(), m.asOf().toString(), m.expiresAt().toString(), Integer.toString(m.total()), m.fingerprint()));
        parts.addAll(List.of(Integer.toString(offset), code, consistency, SourceMetricDiscovery.digest(items))); return WorkflowDefinition.hash(parts);
    }
}
