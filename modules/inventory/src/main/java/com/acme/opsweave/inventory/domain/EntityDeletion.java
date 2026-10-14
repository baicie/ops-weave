package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The operational delete entry for one clearable object.
 *
 * <p>Deleting is an explicit, reasoned, idempotent command, never a background task. For an entity the
 * command is a <em>soft</em> delete: the row stays, so an old Incident or Evidence snapshot keeps
 * resolving the entity it recorded and no relation or history row is cascaded away. An entity that is
 * still an endpoint of a current relation is protected and the command refuses instead of quietly
 * breaking that record.
 */
public final class EntityDeletion {
    private EntityDeletion() {}

    /** What one admitted delete did. Both outcomes keep every referenced record. */
    public enum Outcome {
        SOFT_DELETED("The entity is marked deleted; the row and all references are retained."),
        PROTECTED("The entity is still referenced by a current record and cannot be deleted.");

        private final String summary;

        Outcome(String summary) { this.summary = summary; }

        public String summary() { return summary; }
    }

    public record Command(UUID requestId, EntityId entityId, String reason) {
        public Command {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(entityId, "entityId");
            if (reason == null || reason.isBlank() || reason.length() > 256 || reason.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid entity deletion reason");
            }
        }

        public String commandDigest() {
            return hash(List.of("entity-deletion-command-v1", entityId.value().toString(), reason.trim()));
        }

        private static String hash(List<String> parts) {
            try {
                var md = java.security.MessageDigest.getInstance("SHA-256");
                for (String part : parts) {
                    byte[] bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    md.update((bytes.length + ":").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    md.update(bytes);
                }
                return "sha256:" + java.util.HexFormat.of().formatHex(md.digest());
            } catch (java.security.NoSuchAlgorithmException missing) {
                throw new IllegalStateException();
            }
        }
    }

    /**
     * The immutable acknowledgement. `auditCount` is the number of audit rows retained for this
     * object, and `keptProtected`/`keptUnfinished` record what was deliberately <em>not</em> removed.
     */
    public record Receipt(UUID requestId, String commandDigest, Instant createdAt, EntityId entityId,
                          Outcome outcome, int auditCount, int keptProtected, int keptUnfinished, String reason) {
        public Receipt {
            Objects.requireNonNull(requestId, "requestId");
            if (commandDigest == null || !commandDigest.matches("sha256:[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid deletion digest");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(outcome, "outcome");
            if (auditCount < 1 || keptProtected < 0 || keptUnfinished < 0) throw new IllegalArgumentException("Invalid deletion counts");
            if (reason == null || reason.isBlank() || reason.length() > 256) throw new IllegalArgumentException("Invalid entity deletion reason");
        }

        public void require(Command command) {
            if (!requestId.equals(command.requestId()) || !entityId.equals(command.entityId())
                || !commandDigest.equals(command.commandDigest()) || !reason.equals(command.reason().trim())) {
                throw new Conflict("Entity deletion request reused");
            }
        }
    }

    /** Explicit stable failures; the wire never echoes operator text. */
    public static final class Conflict extends RuntimeException {
        private final String code;
        public Conflict(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
