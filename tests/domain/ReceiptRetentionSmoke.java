import com.acme.opsweave.integration.domain.ReceiptRetention;
import com.acme.opsweave.integration.domain.WorkflowFailure;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Receipt retention budget and explicit cleanup semantics: the budget reports, the preview reads, the
 * confirm is idempotent and protected rows are never candidates. No store is involved here; the real
 * table scan and the deletion itself are covered by the PostgreSQL integration test.
 */
public final class ReceiptRetentionSmoke {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Receipt retention check " + checks); }
    static void rejects(Runnable r) {
        checks++;
        try { r.run(); } catch (IllegalArgumentException | NullPointerException | WorkflowFailure expected) { return; }
        throw new AssertionError("Expected rejection " + checks);
    }
    static void failure(WorkflowFailure.Code code, Runnable r) {
        checks++;
        try { r.run(); } catch (WorkflowFailure expected) { if (expected.code() == code) return; throw new AssertionError(expected.code() + " != " + code); }
        throw new AssertionError("Expected " + code);
    }

    public static void main(String[] args) {
        var defaults = ReceiptRetention.Policy.defaults();
        check(defaults.maxPerScope() == 1000 && defaults.maxPerTenant() == 5000);
        check(ReceiptRetention.Policy.of(null, null).equals(defaults));
        check(new ReceiptRetention.Policy(3, 9).maxPerScope() == 3);
        for (var bad : List.<Runnable>of(
            () -> new ReceiptRetention.Policy(0, 10),
            () -> new ReceiptRetention.Policy(1001, 5000),
            () -> new ReceiptRetention.Policy(10, 5001),
            () -> new ReceiptRetention.Policy(20, 10),
            () -> ReceiptRetention.Policy.of(1001, null))) rejects(bad);
        // The published threshold is visible and the boundary values are exact.
        check(ReceiptRetention.NEAR_LIMIT_PERCENT == 80);
        check(ReceiptRetention.statusOf(0, 1000) == ReceiptRetention.Status.OK);
        check(ReceiptRetention.statusOf(799, 1000) == ReceiptRetention.Status.OK);
        check(ReceiptRetention.statusOf(800, 1000) == ReceiptRetention.Status.NEAR_LIMIT);
        check(ReceiptRetention.statusOf(999, 1000) == ReceiptRetention.Status.NEAR_LIMIT);
        check(ReceiptRetention.statusOf(1000, 1000) == ReceiptRetention.Status.AT_LIMIT);
        rejects(() -> ReceiptRetention.statusOf(-1, 1000));
        rejects(() -> ReceiptRetention.statusOf(1, 0));
        for (var kind : ReceiptRetention.Kind.values()) {
            check(ReceiptRetention.Kind.of(kind.label()) == kind);
        }
        rejects(() -> ReceiptRetention.Kind.of("scan-run"));
        rejects(() -> ReceiptRetention.checkScopeKey(null));
        rejects(() -> ReceiptRetention.checkScopeKey("bad scope"));
        rejects(() -> ReceiptRetention.checkScopeKey("x".repeat(129)));

        var at = Instant.parse("2026-10-13T00:00:00Z");
        var rows = List.of(
            new ReceiptRetention.Row("old-1", at, false),
            new ReceiptRetention.Row("old-2", at.plusSeconds(1), true),
            new ReceiptRetention.Row("old-3", at.plusSeconds(2), false),
            new ReceiptRetention.Row("old-4", at.plusSeconds(3), true));
        var preview = ReceiptRetentionServicePreview(rows, 2);
        check(preview.removable() == 2 && preview.candidates().equals(List.of("old-1", "old-3")));
        check(preview.keptProtected() == 2 && preview.keptUnfinished() == 0);
        check(preview.previewDigest().equals(ReceiptRetention.Preview.digest(preview.kind(), preview.scopeKey(), preview.candidates())));
        check(!preview.previewDigest().equals(ReceiptRetention.Preview.digest(preview.kind(), preview.scopeKey(), List.of("old-3", "old-1"))));
        // A tighter limit removes fewer rows and never reaches into the protected set.
        var narrow = ReceiptRetentionServicePreview(rows, 1);
        check(narrow.removable() == 1 && narrow.candidates().equals(List.of("old-1")) && narrow.keptProtected() == 2);
        // An empty scope is a complete answer: nothing to remove and nothing protected.
        var empty = ReceiptRetentionServicePreview(List.of(), 10);
        check(empty.removable() == 0 && empty.candidates().isEmpty() && empty.previewDigest().startsWith("sha256:"));
        rejects(() -> new ReceiptRetention.Preview(ReceiptRetention.Kind.ONBOARDING, "owner", empty.previewDigest(), List.of(), 0, 0, 0, 0));
        rejects(() -> new ReceiptRetention.Preview(ReceiptRetention.Kind.ONBOARDING, "owner", "sha256:zz", List.of(), 0, 0, 0, 1));
        rejects(() -> new ReceiptRetention.Preview(ReceiptRetention.Kind.ONBOARDING, "owner", empty.previewDigest(), List.of(), 0, 0, 0, 101));

        var report = new ReceiptRetention.Report(ReceiptRetention.Kind.SOURCE_COMMAND, "22222222-2222-4222-8222-222222222222", 800, 200, 1000, 5000);
        check(report.status() == ReceiptRetention.Status.NEAR_LIMIT && report.summary().equals(ReceiptRetention.Status.NEAR_LIMIT.summary()));
        check(new ReceiptRetention.Report(ReceiptRetention.Kind.SOURCE_COMMAND, "scope", 1000, 0, 1000, 5000).status() == ReceiptRetention.Status.AT_LIMIT);
        rejects(() -> new ReceiptRetention.Report(ReceiptRetention.Kind.SOURCE_COMMAND, "bad scope", 1, 0, 1000, 5000));
        rejects(() -> new ReceiptRetention.Report(ReceiptRetention.Kind.SOURCE_COMMAND, "scope", -1, 0, 1000, 5000));

        var command = new ReceiptRetention.Command(UUID.randomUUID(), ReceiptRetention.Kind.ONBOARDING, "owner-1",
            preview.previewDigest(), "Trim superseded onboarding receipts", 50);
        check(command.commandDigest().startsWith("sha256:"));
        check(command.commandDigest().equals(new ReceiptRetention.Command(command.requestId(), ReceiptRetention.Kind.ONBOARDING,
            "owner-1", preview.previewDigest(), "Trim superseded onboarding receipts", 50).commandDigest()));
        check(!command.commandDigest().equals(new ReceiptRetention.Command(command.requestId(), ReceiptRetention.Kind.ONBOARDING,
            "owner-1", preview.previewDigest(), "Another reason", 50).commandDigest()));
        for (var bad : List.<Runnable>of(
            () -> new ReceiptRetention.Command(null, ReceiptRetention.Kind.ONBOARDING, "owner-1", preview.previewDigest(), "r", 1),
            () -> new ReceiptRetention.Command(command.requestId(), ReceiptRetention.Kind.ONBOARDING, "owner-1", preview.previewDigest(), " ", 1),
            () -> new ReceiptRetention.Command(command.requestId(), ReceiptRetention.Kind.ONBOARDING, "owner-1", preview.previewDigest(), "r", 0),
            () -> new ReceiptRetention.Command(command.requestId(), ReceiptRetention.Kind.ONBOARDING, "owner-1", preview.previewDigest(), "r", 101),
            () -> new ReceiptRetention.Command(command.requestId(), ReceiptRetention.Kind.ONBOARDING, "owner-1", "sha256:zz", "r", 1))) rejects(bad);

        var receipt = new ReceiptRetention.Receipt(command.requestId(), command.commandDigest(), at.plusSeconds(9),
            command.kind(), command.scopeKey(), 2, 2, 0, command.reason());
        receipt.require(command);
        checks++;
        rejects(() -> receipt.require(new ReceiptRetention.Command(command.requestId(), ReceiptRetention.Kind.EXTERNAL_LINK,
            "owner-1", preview.previewDigest(), "Trim superseded onboarding receipts", 50)));
        rejects(() -> receipt.require(new ReceiptRetention.Command(command.requestId(), command.kind(), command.scopeKey(),
            preview.previewDigest(), "Different reason", 50)));
        for (var bad : List.<Runnable>of(
            () -> new ReceiptRetention.Receipt(command.requestId(), command.commandDigest(), at, command.kind(), command.scopeKey(), -1, 0, 0, "r"),
            () -> new ReceiptRetention.Receipt(command.requestId(), "sha256:zz", at, command.kind(), command.scopeKey(), 1, 0, 0, "r"),
            () -> new ReceiptRetention.Receipt(command.requestId(), command.commandDigest(), at, command.kind(), command.scopeKey(), 1, 0, 0, " "))) rejects(bad);
        System.out.println("ReceiptRetentionSmoke: " + checks + " checks passed");
    }

    /** The service-level preview rule: oldest cleanable rows first, protected rows never candidates. */
    private static ReceiptRetention.Preview ReceiptRetentionServicePreview(List<ReceiptRetention.Row> rows, int maxRemove) {
        var candidates = new java.util.ArrayList<String>();
        int protectedRows = 0;
        for (var row : rows) {
            if (row.protectedRow()) { protectedRows++; continue; }
            if (candidates.size() < maxRemove) candidates.add(row.id());
        }
        return new ReceiptRetention.Preview(ReceiptRetention.Kind.ONBOARDING, "owner-1",
            ReceiptRetention.Preview.digest(ReceiptRetention.Kind.ONBOARDING, "owner-1", candidates),
            candidates, candidates.size(), protectedRows, 0, maxRemove);
    }
}
