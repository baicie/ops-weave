import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.inventory.api.SourceReceiptCapacityReader;
import com.acme.opsweave.inventory.application.SourceReceiptCapacityService;
import com.acme.opsweave.inventory.application.SourceReviewService;
import com.acme.opsweave.inventory.domain.SourceBindingCorrection;
import com.acme.opsweave.inventory.domain.SourceReceiptCapacity;
import com.acme.opsweave.inventory.domain.SourceSnapshot;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.List;
import java.util.Set;

/**
 * The receipt caps fail closed once a source reaches them, so the operator needs to see the count
 * coming. This view only reports: it prunes nothing, raises no cap and replays no write.
 */
public final class ReceiptCapacitySmoke {
    private static final TenantId TENANT = new TenantId("tenant-capacity");
    private static final SubjectId ACTOR = new SubjectId("operator");
    private static final String SOURCE = "cmdb-import";
    private static int checks = 0;

    public static void main(String[] args) {
        require(SourceReceiptCapacity.cap(SourceReceiptCapacity.Kind.SNAPSHOT) == SourceSnapshot.MAX_RECEIPTS,
            "the snapshot cap is the one the write path enforces");
        require(SourceReceiptCapacity.cap(SourceReceiptCapacity.Kind.BINDING_CORRECTION) == SourceBindingCorrection.MAX_RECEIPTS,
            "the correction cap is the one the write path enforces");
        require(SourceReceiptCapacity.NEAR_LIMIT_PERCENT == 80, "the warning threshold is published");

        // Status boundaries: nothing, just under the warning line, exactly at it, and at the cap.
        require(status(0, 1000) == SourceReceiptCapacity.Status.OK, "an empty scope is fine");
        require(status(799, 1000) == SourceReceiptCapacity.Status.OK, "just under the threshold is fine");
        require(status(800, 1000) == SourceReceiptCapacity.Status.NEAR_LIMIT, "the threshold warns");
        require(status(999, 1000) == SourceReceiptCapacity.Status.NEAR_LIMIT, "one short of the cap warns");
        require(status(1000, 1000) == SourceReceiptCapacity.Status.AT_LIMIT, "the cap is reached");
        require(status(0, 1) == SourceReceiptCapacity.Status.OK, "an empty scope is never reported as nearly full");
        require(status(1, 1) == SourceReceiptCapacity.Status.AT_LIMIT, "a cap of one is reached by one receipt");

        // The wording is fixed per status and never claims a cleanup happened.
        var atLimit = capacity(SourceReceiptCapacity.Kind.SNAPSHOT, 1000, 1000);
        require(atLimit.status() == SourceReceiptCapacity.Status.AT_LIMIT
                && atLimit.summary().equals(SourceReceiptCapacity.Kind.SNAPSHOT.atLimitSummary()),
            "the at-limit wording comes from the cap that is reached");
        require(!atLimit.summary().contains("removed") && !atLimit.summary().contains("pruned"),
            "the wording never claims receipts were removed");
        require(capacity(SourceReceiptCapacity.Kind.BINDING_CORRECTION, 10, 1000).status() == SourceReceiptCapacity.Status.OK,
            "the two kinds are counted separately");
        require(capacity(SourceReceiptCapacity.Kind.SNAPSHOT, 900, 1000).summary()
                .equals(SourceReceiptCapacity.Status.NEAR_LIMIT.summary()), "a warning explains itself in fixed text");

        // The record refuses anything that would misreport capacity.
        fails(IllegalArgumentException.class, () -> capacity(SourceReceiptCapacity.Kind.SNAPSHOT, -1, 1000));
        fails(IllegalArgumentException.class, () -> capacity(SourceReceiptCapacity.Kind.SNAPSHOT, 1001, 1000));
        fails(IllegalArgumentException.class, () -> capacity(SourceReceiptCapacity.Kind.SNAPSHOT, 0, 0));
        fails(IllegalArgumentException.class, () -> new SourceReceiptCapacity(
            SourceReceiptCapacity.Kind.SNAPSHOT, "bad source!", 0, 1000));
        fails(NullPointerException.class, () -> new SourceReceiptCapacity(null, SOURCE, 0, 1000));

        // Authorization matches the writes the cap protects.
        var reader = new Principal(ACTOR, TENANT, Set.of(Permission.ENTITY_READ, Permission.ENTITY_MANAGE, Permission.SOURCE_SYNC),
            ResourceScope.tenantWide());
        var counts = new Counting(3, 999);
        var service = new SourceReceiptCapacityService(new AuthorizeUseCase(), counts, SOURCE);
        List<SourceReceiptCapacity> reported = service.capacity(reader);
        require(reported.size() == 2, "both caps are reported");
        require(reported.getFirst().kind() == SourceReceiptCapacity.Kind.SNAPSHOT
                && reported.getFirst().kept() == 3
                && reported.getFirst().max() == SourceReceiptCapacity.cap(SourceReceiptCapacity.Kind.SNAPSHOT),
            "the snapshot row reports the counted value and the published cap");
        require(reported.getLast().kind() == SourceReceiptCapacity.Kind.BINDING_CORRECTION
                && reported.getLast().kept() == 999
                && reported.getLast().max() == SourceReceiptCapacity.cap(SourceReceiptCapacity.Kind.BINDING_CORRECTION),
            "the correction row reports the counted value and the published cap");
        require(reported.getLast().status() == SourceReceiptCapacity.Status.NEAR_LIMIT
                && reported.getFirst().status() == SourceReceiptCapacity.Status.OK,
            "each row carries its own status");
        require(counts.calls() == 2, "the view counts without writing");

        fails(SourceReviewService.Access.class, () -> service.capacity(null));
        fails(SourceReviewService.Access.class, () -> service.capacity(new Principal(ACTOR, TENANT,
            Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide())));
        fails(SourceReviewService.Access.class, () -> service.capacity(new Principal(ACTOR, TENANT,
            Set.of(Permission.ENTITY_READ, Permission.ENTITY_MANAGE), ResourceScope.tenantWide())));
        fails(IllegalArgumentException.class, () -> new SourceReceiptCapacityService(new AuthorizeUseCase(), counts, "  "));

        System.out.println("ReceiptCapacitySmoke: " + checks + " checks passed");
    }

    private static SourceReceiptCapacity capacity(SourceReceiptCapacity.Kind kind, int kept, int max) {
        return new SourceReceiptCapacity(kind, SOURCE, kept, max);
    }

    private static SourceReceiptCapacity.Status status(int kept, int max) {
        return capacity(SourceReceiptCapacity.Kind.SNAPSHOT, kept, max).status();
    }

    /** Counts how often the view was read, so "counting only" is observable. */
    private static final class Counting implements SourceReceiptCapacityReader {
        private final int snapshots;
        private final int corrections;
        private int calls;

        private Counting(int snapshots, int corrections) {
            this.snapshots = snapshots;
            this.corrections = corrections;
        }

        public int snapshotReceipts(TenantId tenantId, String sourceInstanceId) {
            calls++;
            return tenantId.equals(TENANT) && sourceInstanceId.equals(SOURCE) ? snapshots : 0;
        }

        public int correctionReceipts(TenantId tenantId, String sourceInstanceId) {
            calls++;
            return tenantId.equals(TENANT) && sourceInstanceId.equals(SOURCE) ? corrections : 0;
        }

        private int calls() {
            return calls;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
        checks++;
    }

    private static void fails(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            if (!type.isInstance(expected)) {
                throw new IllegalStateException("expected " + type.getSimpleName() + " but got " + expected.getClass().getSimpleName());
            }
            checks++;
            return;
        }
        throw new IllegalStateException("Expected " + type.getSimpleName());
    }
}
