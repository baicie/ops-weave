package com.acme.opsweave.inventory.api;

import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;

/** Implementations serialize review decisions with ingestion for the same entity. */
public interface SourceReviewStore {
    record Import(UUID id, long expectedEntityVersion, ExternalObjectKey source, Instant observedAt,
                  Map<String,String> values, String mappingDigest, String actor, AssetIdentity.Pin identity) {
        public Import(UUID id, long expectedEntityVersion, ExternalObjectKey source, Instant observedAt, Map<String,String> values, String mappingDigest, String actor) {
            this(id,expectedEntityVersion,source,observedAt,values,mappingDigest,actor,null);
        }
        public Import { Objects.requireNonNull(id); Objects.requireNonNull(source); Objects.requireNonNull(observedAt);
            if (expectedEntityVersion < 1) throw new IllegalArgumentException("Invalid entity version");
            values = SourceReview.fields(values, false); SourceReview.bounded(actor, 128);
            if (mappingDigest == null || !mappingDigest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid digest");
        }
        public boolean matches(SourceReview review) { return id.equals(review.id()) && expectedEntityVersion == review.baseVersion() && source.equals(review.source())
            && observedAt.equals(review.observedAt()) && values.equals(review.values()) && mappingDigest.equals(review.mappingDigest()) && actor.equals(review.actor()) && Objects.equals(identity,review.identity()); }
        public SourceReview stage(Entity primary, long currentVersion, Instant now) {
            if (expectedEntityVersion != currentVersion) throw new SourceReview.Conflict("Entity version changed");
            if (!"Host".equals(primary.entityType()) && !"host".equals(primary.entityType())) throw new IllegalArgumentException("Supplemental imports require a Host");
            if (primary.attributes().containsKey("fieldAuthority")) throw new IllegalStateException("Expected primary source snapshot");
            if (observedAt.isAfter(now) || !observedAt.plus(SourceReview.MAX_AGE).isAfter(now)) throw new IllegalArgumentException("Import must be observed within seven days");
            return new SourceReview(id, source.tenantId(), primary.id(), currentVersion, source, observedAt, now, values, FieldAuthority.fields(primary), mappingDigest, actor, List.of(), identity);
        }
    }
    record Receipt(UUID reviewId, SourceReview.Command command, SourceReview result) {}
    record Page(List<SourceReview> items, SourceReview active) { public Page { items = List.copyOf(items); } }
    SourceReview stage(TenantId tenant, EntityId entity, Import input, Instant now);
    SourceReview decide(TenantId tenant, EntityId entity, String sourceId, UUID reviewId, SourceReview.Command command, Instant now);
    Page reviews(TenantId tenant, EntityId entity, String sourceId, UUID after, int limit);
}
