import com.acme.opsweave.inventory.api.RejectedWriteAttemptStore;
import com.acme.opsweave.inventory.api.SourceReviewStore;
import com.acme.opsweave.inventory.api.SourceSnapshotStore;
import com.acme.opsweave.inventory.domain.AssetIdentity;
import com.acme.opsweave.inventory.domain.ExternalObjectKey;
import com.acme.opsweave.inventory.domain.RejectedWriteAttempt;
import com.acme.opsweave.inventory.domain.SourceBindingCorrection;
import com.acme.opsweave.inventory.domain.SourceReview;
import com.acme.opsweave.inventory.domain.SourceSnapshot;
import com.acme.opsweave.inventory.infrastructure.AuditedSourceStores;
import com.acme.opsweave.inventory.infrastructure.InMemoryRejectedWriteAttemptStore;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Refused supplemental-source writes are recorded without storing a field value or a vendor message,
 * the refusal itself is unchanged, and the log stays bounded per tenant and source.
 */
public final class RejectedWriteAuditSmoke {
    private static final TenantId TENANT = new TenantId("tenant-audit");
    private static final TenantId OTHER = new TenantId("tenant-audit-other");
    private static final String SOURCE = "cmdb-1";
    private static final EntityId ENTITY = new EntityId(UUID.randomUUID());
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC);
    private static final String DIGEST = "sha256:" + "ab".repeat(32);
    private static int checks = 0;

    public static void main(String[] args) {
        require(RejectedWriteAttempt.Policy.DEFAULT_MAX_PER_SOURCE == 500
                && RejectedWriteAttempt.Policy.CEILING == 5000, "the published audit budget stays at 500 per source");
        require(RejectedWriteAttempt.Policy.of(null).maxPerSource() == 500, "an unset budget is the default");
        require(RejectedWriteAttempt.Policy.of(10).maxPerSource() == 10, "the budget can be tightened");
        fails(IllegalArgumentException.class, () -> RejectedWriteAttempt.Policy.of(0));
        fails(IllegalArgumentException.class, () -> RejectedWriteAttempt.Policy.of(5001));

        var audit = new InMemoryRejectedWriteAttemptStore();
        var reviews = AuditedSourceStores.reviews(new RejectingReview(), audit, CLOCK, SOURCE);
        var snapshots = AuditedSourceStores.snapshots(new RejectingSnapshot(), audit, CLOCK, SOURCE);

        // A refused review is rethrown unchanged and recorded under a stable code with field names only.
        var importInput = new SourceReviewStore.Import(UUID.randomUUID(), 1, key(TENANT), instant("2026-09-26T09:00:00Z"),
            Map.of("ip", "10.0.0.7"), DIGEST, "operator");
        SourceReview.Conflict reviewRefusal = fails(SourceReview.Conflict.class,
            () -> reviews.stage(TENANT, ENTITY, importInput, CLOCK.instant()));
        require(reviewRefusal.getMessage().equals("Entity version changed"), "the refusal keeps its own message");
        require(audit.kept(TENANT, SOURCE) == 1, "one refusal is recorded");
        var staged = audit.recent(TENANT, SOURCE, 10).getFirst();
        require(staged.kind() == RejectedWriteAttempt.Kind.FIELD_REVIEW
                && staged.method() == RejectedWriteAttempt.Method.STAGE_REVIEW
                && staged.code() == RejectedWriteAttempt.Code.ENTITY_VERSION_CHANGED
                && staged.actor().equals("operator")
                && staged.fieldNames().equals(List.of("ip"))
                && staged.attemptedAt().equals(CLOCK.instant()), "the audit keeps code, method, actor and field names");
        require(!staged.toString().contains("10.0.0.7"), "the audit never carries a field value");

        // A decision refusal records the review state reason, not the caller's message.
        var command = new SourceReview.Command(UUID.randomUUID(), SourceReview.Action.ACCEPT, 1, 1,
            Map.of("ip", SourceReview.Choice.SUPPLEMENTAL), "reason", "operator");
        fails(SourceReview.Conflict.class, () -> reviews.decide(TENANT, ENTITY, SOURCE, UUID.randomUUID(), command, CLOCK.instant()));
        require(audit.kept(TENANT, SOURCE) == 2, "the decision refusal is recorded");
        require(codeOf(audit, RejectedWriteAttempt.Method.DECIDE_REVIEW) == RejectedWriteAttempt.Code.REVIEW_STATE_CHANGED,
            "a decision refusal records the review state reason");

        // Binding corrections record both conflict families and never a value.
        var correction = new SourceBindingCorrection.Command(UUID.randomUUID(), "host-7", UUID.randomUUID(), ENTITY, 3,
            new EntityId(UUID.randomUUID()), 4, pin(), instant("2026-09-26T09:30:00Z"), Map.of("owner", "sre"), "fix");
        fails(SourceBindingCorrection.Conflict.class,
            () -> snapshots.correct(TENANT, "operator", SOURCE, "cmdb", correction, DIGEST));
        var corrected = recordOf(audit, RejectedWriteAttempt.Method.CORRECT_BINDING);
        require(corrected.kind() == RejectedWriteAttempt.Kind.BINDING_CORRECTION
                && corrected.code() == RejectedWriteAttempt.Code.BINDING_FIELDS_ACTIVE
                && corrected.fieldNames().equals(List.of("owner")), "a correction refusal records its own code");
        fails(SourceSnapshot.Conflict.class,
            () -> snapshots.ingest(TENANT, "operator", SOURCE, "cmdb", snapshotInput(), DIGEST));
        var ingested = recordOf(audit, RejectedWriteAttempt.Method.INGEST_SNAPSHOT);
        require(ingested.code() == RejectedWriteAttempt.Code.SNAPSHOT_OUTDATED
                && ingested.kind() == RejectedWriteAttempt.Kind.FIELD_REVIEW
                && ingested.fieldNames().equals(List.of("ip")), "a snapshot refusal records the snapshot code");

        // Reads and successful writes are not refusals: they leave no audit row.
        int before = audit.kept(TENANT, SOURCE);
        var passing = AuditedSourceStores.reviews(new PassingReview(), audit, CLOCK, SOURCE);
        passing.stage(TENANT, ENTITY, importInput, CLOCK.instant());
        passing.decide(TENANT, ENTITY, SOURCE, UUID.randomUUID(),
            new SourceReview.Command(UUID.randomUUID(), SourceReview.Action.REJECT, 1, 1, Map.of(), "no", "operator"),
            CLOCK.instant());
        require(audit.kept(TENANT, SOURCE) == before, "a successful write is not audited as a refusal");

        // A caller without a usable actor is recorded as unknown rather than echoing bad input.
        fails(SourceReview.Conflict.class, () -> AuditedSourceStores
            .reviews(new RejectingReview(), audit, CLOCK, SOURCE)
            .stage(TENANT, ENTITY, new SourceReviewStore.Import(UUID.randomUUID(), 1, key(TENANT),
                instant("2026-09-26T09:00:00Z"), Map.of("name", "db-1"), DIGEST, "  bad  "), CLOCK.instant()));
        var stageRefusals = recordsOf(audit, RejectedWriteAttempt.Method.STAGE_REVIEW);
        require(stageRefusals.size() == 2, "both stage refusals are recorded");
        require(stageRefusals.stream().anyMatch(record -> record.actor().equals("unknown")),
            "an unusable actor is recorded as unknown");
        require(stageRefusals.stream().noneMatch(record -> record.actor().contains("bad")),
            "no record echoes an unusable actor");

        // Bounded, newest-first, tenant and source isolated.
        var bounded = new InMemoryRejectedWriteAttemptStore(new RejectedWriteAttempt.Policy(3));
        for (int index = 0; index < 5; index++) {
            bounded.record(attempt(TENANT, SOURCE, RejectedWriteAttempt.Code.BINDING_CHANGED, Instant.ofEpochSecond(1000 + index)));
        }
        require(bounded.kept(TENANT, SOURCE) == 3, "the scope never holds more than its budget");
        require(bounded.recent(TENANT, SOURCE, 10).size() == 3, "reads see at most the budget");
        require(bounded.recent(TENANT, SOURCE, 10).getFirst().attemptedAt().equals(Instant.ofEpochSecond(1004)),
            "the newest refusal is first");
        require(bounded.recent(TENANT, SOURCE, 10).getLast().attemptedAt().equals(Instant.ofEpochSecond(1002)),
            "the oldest refusals were pruned");
        bounded.record(attempt(OTHER, SOURCE, RejectedWriteAttempt.Code.BINDING_CHANGED, Instant.ofEpochSecond(2000)));
        bounded.record(attempt(TENANT, "cmdb-2", RejectedWriteAttempt.Code.BINDING_CHANGED, Instant.ofEpochSecond(2000)));
        require(bounded.kept(OTHER, SOURCE) == 1 && bounded.kept(TENANT, "cmdb-2") == 1,
            "another tenant or source keeps its own log");
        require(bounded.kept(TENANT, SOURCE) == 3, "another scope's record never evicts this scope's rows");
        fails(IllegalArgumentException.class, () -> bounded.recent(TENANT, SOURCE, 0));
        fails(IllegalArgumentException.class, () -> bounded.recent(TENANT, SOURCE, RejectedWriteAttemptStore.MAX_RECENT + 1));

        // The record itself refuses anything that would leak or claim too much.
        fails(IllegalArgumentException.class, () -> attempt(TENANT, "bad source!", RejectedWriteAttempt.Code.BINDING_CHANGED, Instant.EPOCH));
        fails(IllegalArgumentException.class, () -> attempt(OTHER, SOURCE, RejectedWriteAttempt.Code.BINDING_CHANGED, Instant.EPOCH, " "));
        fails(IllegalArgumentException.class, () -> attempt(OTHER, SOURCE, RejectedWriteAttempt.Code.BINDING_CHANGED, Instant.EPOCH, "operator",
            List.of("ip", "ip")));
        fails(IllegalArgumentException.class, () -> attempt(OTHER, SOURCE, RejectedWriteAttempt.Code.BINDING_CHANGED, Instant.EPOCH, "operator",
            List.of("<script>")));
        fails(IllegalArgumentException.class, () -> attempt(OTHER, SOURCE, RejectedWriteAttempt.Code.BINDING_CHANGED, Instant.EPOCH, "operator",
            java.util.stream.IntStream.range(0, RejectedWriteAttempt.MAX_FIELDS + 1).mapToObj(index -> "f" + index).toList()));

        // A source nobody can read back is not audited at all: the log is scoped to the configured source.
        var configuredOnly = new InMemoryRejectedWriteAttemptStore();
        var foreignSource = new SourceReviewStore.Import(UUID.randomUUID(), 1,
            new ExternalObjectKey(TENANT, "cmdb-other", "cmdb-host", "host-9", "1"),
            instant("2026-09-26T09:00:00Z"), Map.of("ip", "10.0.0.8"), DIGEST, "operator");
        fails(SourceReview.Conflict.class, () -> AuditedSourceStores
            .reviews(new RejectingReview(), configuredOnly, CLOCK, SOURCE)
            .stage(TENANT, ENTITY, foreignSource, CLOCK.instant()));
        require(configuredOnly.kept(TENANT, SOURCE) == 0 && configuredOnly.kept(TENANT, "cmdb-other") == 0,
            "a refusal outside the configured source leaves no unreadable audit row");

        System.out.println("RejectedWriteAuditSmoke: " + checks + " checks passed");
    }

    private static List<RejectedWriteAttempt> recordsOf(RejectedWriteAttemptStore store, RejectedWriteAttempt.Method method) {
        return store.recent(TENANT, SOURCE, RejectedWriteAttemptStore.MAX_RECENT).stream()
            .filter(record -> record.method() == method)
            .toList();
    }

    private static RejectedWriteAttempt recordOf(RejectedWriteAttemptStore store, RejectedWriteAttempt.Method method) {
        return recordsOf(store, method).getFirst();
    }

    private static RejectedWriteAttempt.Code codeOf(RejectedWriteAttemptStore store, RejectedWriteAttempt.Method method) {
        return recordOf(store, method).code();
    }

    private static ExternalObjectKey key(TenantId tenant) {
        return new ExternalObjectKey(tenant, SOURCE, "cmdb-host", "host-7", "1");
    }

    /** A canonical scoped asset UUID pinned to the audit test namespace. */
    private static AssetIdentity.Pin pin() {
        return new AssetIdentity.Pin(UUID.randomUUID(), "ops-assets", "7f1c2f5a-9b3d-4c6e-8a10-2d4b6c8e0f13", 1);
    }

    private static Instant instant(String value) {
        return Instant.parse(value);
    }

    private static SourceSnapshot.Input snapshotInput() {
        return new SourceSnapshot.Input(UUID.randomUUID(), instant("2026-09-26T09:00:00Z"), false,
            List.of(new SourceSnapshot.Row("host-7", UUID.randomUUID().toString(), Map.of("ip", "10.0.0.7"))));
    }

    private static RejectedWriteAttempt attempt(TenantId tenant, String source, RejectedWriteAttempt.Code code, Instant at) {
        return attempt(tenant, source, code, at, "operator", List.of("ip"));
    }

    private static RejectedWriteAttempt attempt(TenantId tenant, String source, RejectedWriteAttempt.Code code, Instant at, String actor) {
        return attempt(tenant, source, code, at, actor, List.of("ip"));
    }

    private static RejectedWriteAttempt attempt(TenantId tenant, String source, RejectedWriteAttempt.Code code, Instant at, String actor, List<String> fields) {
        return new RejectedWriteAttempt(UUID.randomUUID(), tenant, source, RejectedWriteAttempt.Kind.BINDING_CORRECTION,
            RejectedWriteAttempt.Method.CORRECT_BINDING, code, actor, fields, at);
    }

    /** Refuses every write, the way a version or binding clash does. */
    private static final class RejectingReview implements SourceReviewStore {
        public SourceReview stage(TenantId tenant, EntityId entity, Import input, Instant now) {
            throw new SourceReview.Conflict("Entity version changed");
        }

        public SourceReview decide(TenantId tenant, EntityId entity, String sourceId, UUID reviewId, SourceReview.Command command, Instant now) {
            throw new SourceReview.Conflict("Review is not in the required state");
        }

        public Page reviews(TenantId tenant, EntityId entity, String sourceId, UUID after, int limit) {
            return new Page(List.of(), null);
        }
    }

    /** Accepts every write, so the audit can prove it records refusals only. */
    private static final class PassingReview implements SourceReviewStore {
        public SourceReview stage(TenantId tenant, EntityId entity, Import input, Instant now) {
            return new SourceReview(input.id(), tenant, entity, input.expectedEntityVersion(), input.source(), input.observedAt(), now,
                input.values(), Map.of(), input.mappingDigest(), input.actor(), List.of());
        }

        public SourceReview decide(TenantId tenant, EntityId entity, String sourceId, UUID reviewId, SourceReview.Command command, Instant now) {
            var review = new SourceReview(reviewId, tenant, entity, 1, new ExternalObjectKey(tenant, sourceId, "cmdb-host", "host-7", "1"),
                now.minusSeconds(60), now.minusSeconds(30), Map.of("ip", "10.0.0.7"), Map.of(), DIGEST, "operator", List.of());
            return review.decide(command, 1, now);
        }

        public Page reviews(TenantId tenant, EntityId entity, String sourceId, UUID after, int limit) {
            return new Page(List.of(), null);
        }
    }

    /** Refuses the snapshot and the correction with their own stable codes. */
    private static final class RejectingSnapshot implements SourceSnapshotStore {
        public SourceSnapshot.Receipt ingest(TenantId tenant, String actor, String source, String namespace, SourceSnapshot.Input input, String mappingDigest) {
            throw new SourceSnapshot.Conflict(SourceSnapshot.Code.SNAPSHOT_OUTDATED);
        }

        public Optional<SourceSnapshot.Receipt> receipt(TenantId tenant, String actor, String source, String namespace, UUID requestId) {
            return Optional.empty();
        }

        public Page presence(TenantId tenant, EntityId entity, String source) {
            return new Page(CLOCK.instant(), List.of());
        }

        public SourceBindingCorrection.Receipt correct(TenantId tenant, String actor, String source, String namespace, SourceBindingCorrection.Command command, String mappingDigest) {
            throw new SourceBindingCorrection.Conflict(SourceBindingCorrection.Code.BINDING_FIELDS_ACTIVE);
        }

        public Optional<SourceBindingCorrection.Receipt> correction(TenantId tenant, String actor, String source, String namespace, UUID requestId) {
            return Optional.empty();
        }

        public List<SourceBindingCorrection.Receipt> corrections(TenantId tenant, String source, String namespace, EntityId entity, UUID after, int limit) {
            return List.of();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
        checks++;
    }

    private static <T extends RuntimeException> T fails(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            if (!type.isInstance(expected)) {
                throw new IllegalStateException("expected " + type.getSimpleName() + " but got " + expected.getClass().getSimpleName());
            }
            checks++;
            return type.cast(expected);
        }
        throw new IllegalStateException("Expected " + type.getSimpleName());
    }
}
