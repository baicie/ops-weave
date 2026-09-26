package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

/** Explicit bounded import, not a claim that a vendor API or consistent upstream scan is connected. */
public final class SourceSnapshot {
    private SourceSnapshot() {}
    public static final String ENGINE = "registered-cmdb-snapshot-v1";
    public static final int MAX_RECORDS = 100, MAX_RECEIPTS = 1000;
    public static final Duration MAX_AGE = Duration.ofDays(7);
    public record Row(String externalId, String assetUuid, Map<String,String> values) {
        public Row { externalId=SourceReview.bounded(externalId,256);assetUuid=AssetIdentity.value(assetUuid);values=SourceReview.fields(values,false); }
    }
    public record Input(UUID requestId, Instant observedAt, boolean complete, List<Row> records) {
        public Input {
            Objects.requireNonNull(requestId);Objects.requireNonNull(observedAt);records=List.copyOf(records);
            if(observedAt.isBefore(Instant.EPOCH) || records.size()>MAX_RECORDS)throw new IllegalArgumentException("Invalid snapshot bounds");
            if(records.stream().map(Row::externalId).distinct().count()!=records.size() || records.stream().map(Row::assetUuid).distinct().count()!=records.size())throw new IllegalArgumentException("Duplicate snapshot identity");
        }
        public void requireFresh(Instant now) { if(observedAt.isAfter(now) || !observedAt.plus(MAX_AGE).isAfter(now))throw new IllegalArgumentException("Snapshot time must be within seven days"); }
    }
    public record Resolved(String externalId, EntityId entityId, AssetIdentity.Pin identity, UUID reviewId, long entityVersion) {
        public Resolved {SourceReview.bounded(externalId,256);Objects.requireNonNull(entityId);Objects.requireNonNull(identity);Objects.requireNonNull(reviewId);if(entityVersion<1)throw new IllegalArgumentException("Invalid entity version");}
    }
    public record Receipt(TenantId tenantId, String actor, String sourceInstanceId, String namespace, Input input,
            Instant ingestedAt, String mappingDigest, List<Resolved> resolved, int markedAbsent) {
        public Receipt {Objects.requireNonNull(tenantId);SourceReview.bounded(actor,128);new SourceScan.Scope(tenantId,sourceInstanceId,"cmdb-host");AssetIdentity.namespace(namespace);
            Objects.requireNonNull(input);Objects.requireNonNull(ingestedAt);resolved=List.copyOf(resolved);
            input.requireFresh(ingestedAt);
            if(resolved.size()!=input.records().size() || markedAbsent<0 || markedAbsent>MAX_RECORDS || (!input.complete() && markedAbsent!=0)
                || mappingDigest==null || !mappingDigest.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid snapshot receipt");
            for(int i=0;i<resolved.size();i++)if(!resolved.get(i).externalId().equals(input.records().get(i).externalId()) || !resolved.get(i).identity().value().equals(input.records().get(i).assetUuid()) || !resolved.get(i).identity().namespace().equals(namespace))throw new IllegalArgumentException("Snapshot resolution mismatch");
        }
    }
    public record Presence(TenantId tenantId, EntityId entityId, String sourceInstanceId, String externalId, AssetIdentity.Pin identity,
            Instant observedAt, Instant ingestedAt, boolean present, UUID snapshotId) {
        public Presence {Objects.requireNonNull(tenantId);Objects.requireNonNull(entityId);new SourceScan.Scope(tenantId,sourceInstanceId,"cmdb-host");SourceReview.bounded(externalId,256);
            Objects.requireNonNull(identity);Objects.requireNonNull(observedAt);Objects.requireNonNull(ingestedAt);Objects.requireNonNull(snapshotId);
            if(observedAt.isBefore(Instant.EPOCH) || observedAt.isAfter(ingestedAt))throw new IllegalArgumentException("Invalid presence time");}
        public Instant expiresAt(){return observedAt.plus(MAX_AGE);}
        public String status(Instant now,boolean identityActive){return !identityActive?"IDENTITY_REVOKED":!expiresAt().isAfter(now)?"STALE":present?"PRESENT":"ABSENT";}
    }
    public enum Code { IDENTITY_UNRESOLVED, BINDING_CONFLICT, SNAPSHOT_OUTDATED, REQUEST_CONFLICT, SNAPSHOT_LIMIT }
    public static final class Conflict extends RuntimeException {
        private final Code code;
        public Conflict(Code code){super(code.name());this.code=code;}
        public Code code(){return code;}
    }
}
