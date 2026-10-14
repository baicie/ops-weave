import com.acme.opsweave.inventory.domain.EntityDeletion;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Operational delete command semantics: reasoned, idempotent, and never a cascade. */
public final class EntityDeletionSmoke {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Entity deletion check " + checks); }
    static void rejects(Runnable r) {
        checks++;
        try { r.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Expected rejection " + checks);
    }

    public static void main(String[] args) {
        var id = new EntityId(UUID.fromString("10000000-0000-4000-8000-000000000001"));
        var at = Instant.parse("2026-10-13T00:00:00Z");
        var command = new EntityDeletion.Command(UUID.randomUUID(), id, "Retired by operations");
        check(command.commandDigest().matches("sha256:[a-f0-9]{64}"));
        check(command.commandDigest().equals(new EntityDeletion.Command(command.requestId(), id, "Retired by operations").commandDigest()));
        check(!command.commandDigest().equals(new EntityDeletion.Command(command.requestId(), id, "Another reason").commandDigest()));
        check(!command.commandDigest().equals(new EntityDeletion.Command(command.requestId(),
            new EntityId(UUID.fromString("10000000-0000-4000-8000-000000000002")), "Retired by operations").commandDigest()));
        for (var bad : List.<Runnable>of(
            () -> new EntityDeletion.Command(null, id, "r"),
            () -> new EntityDeletion.Command(command.requestId(), null, "r"),
            () -> new EntityDeletion.Command(command.requestId(), id, null),
            () -> new EntityDeletion.Command(command.requestId(), id, " "),
            () -> new EntityDeletion.Command(command.requestId(), id, "x".repeat(257)),
            () -> new EntityDeletion.Command(command.requestId(), id, "line\nbreak"))) rejects(bad);

        // A soft delete keeps every referenced record; the counts make that visible on the receipt.
        var receipt = new EntityDeletion.Receipt(command.requestId(), command.commandDigest(), at, id,
            EntityDeletion.Outcome.SOFT_DELETED, 1, 0, 0, command.reason());
        check(receipt.auditCount() == 1 && receipt.keptProtected() == 0 && receipt.keptUnfinished() == 0);
        check(receipt.outcome().summary().contains("retained"));
        check(EntityDeletion.Outcome.PROTECTED.summary().contains("cannot be deleted"));
        receipt.require(command);
        checks++;
        rejects(() -> receipt.require(new EntityDeletion.Command(command.requestId(), id, "Different reason")));
        rejects(() -> receipt.require(new EntityDeletion.Command(command.requestId(),
            new EntityId(UUID.fromString("10000000-0000-4000-8000-000000000003")), "Retired by operations")));
        for (var bad : List.<Runnable>of(
            () -> new EntityDeletion.Receipt(command.requestId(), "sha256:zz", at, id, EntityDeletion.Outcome.SOFT_DELETED, 1, 0, 0, "r"),
            () -> new EntityDeletion.Receipt(command.requestId(), command.commandDigest(), at, id, EntityDeletion.Outcome.SOFT_DELETED, 0, 0, 0, "r"),
            () -> new EntityDeletion.Receipt(command.requestId(), command.commandDigest(), at, id, EntityDeletion.Outcome.SOFT_DELETED, 1, -1, 0, "r"),
            () -> new EntityDeletion.Receipt(command.requestId(), command.commandDigest(), at, id, null, 1, 0, 0, "r"),
            () -> new EntityDeletion.Receipt(command.requestId(), command.commandDigest(), at, id, EntityDeletion.Outcome.SOFT_DELETED, 1, 0, 0, " "))) rejects(bad);
        System.out.println("EntityDeletionSmoke: " + checks + " checks passed");
    }
}
