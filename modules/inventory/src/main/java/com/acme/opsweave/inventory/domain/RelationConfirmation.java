package com.acme.opsweave.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** One independent confirmation entry on a single relation instance record. Source confirmations and
 *  the manual assertion are separate entries; a withdrawal only withdraws its own entry and never
 *  rewrites another entry's recorded text. The entry identity is deterministic: source kind plus the
 *  explicit source identity plus the batch/execution reference, so cross-execution replays do not
 *  double count. */
public record RelationConfirmation(String sourceKind, String sourceKey, String batchRef, String dataMode,
        Instant confirmedAt, Instant withdrawnAt, String withdrawReason) {
    public static final Set<String> SOURCE_KINDS = Set.of("manual", "source");
    public static final Set<String> DATA_MODES = Set.of("fixture", "zabbix-jsonrpc", "import", "unknown");
    public static final int MAX_ENTRIES_PER_RELATION = 50;

    public RelationConfirmation {
        if (sourceKind == null || !SOURCE_KINDS.contains(sourceKind)) throw new IllegalArgumentException("Invalid relation confirmation source kind");
        checkText(sourceKey, 128, "Invalid relation confirmation source key");
        checkText(batchRef, 128, "Invalid relation confirmation batch reference");
        if (dataMode == null || !DATA_MODES.contains(dataMode)) throw new IllegalArgumentException("Invalid relation confirmation data mode");
        Objects.requireNonNull(confirmedAt, "Confirmation time is required");
        if ((withdrawnAt == null) != (withdrawReason == null)) throw new IllegalArgumentException("Relation confirmation withdrawal requires time and reason");
        if (withdrawReason != null) {
            Objects.requireNonNull(withdrawnAt);
            if (withdrawnAt.isBefore(confirmedAt)) throw new IllegalArgumentException("Invalid relation confirmation withdrawal time");
            if (withdrawReason.isBlank() || withdrawReason.length() > 256 || withdrawReason.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid relation confirmation withdrawal reason");
        }
    }

    /** Deterministic entry identity: source kind + explicit source identity + batch/execution reference. */
    public Key key() { return new Key(sourceKind, sourceKey, batchRef); }
    public boolean withdrawn() { return withdrawnAt != null; }

    /** A withdrawal marks exactly this entry and keeps the recorded confirmation text untouched. */
    public RelationConfirmation withdraw(Instant at, String reason) {
        if (withdrawn()) throw new IllegalArgumentException("Relation confirmation already withdrawn");
        return new RelationConfirmation(sourceKind, sourceKey, batchRef, dataMode, confirmedAt, at, reason);
    }

    private static void checkText(String value, int max, String message) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(message);
    }

    public record Key(String sourceKind, String sourceKey, String batchRef) {
        public Key {
            if (sourceKind == null || !SOURCE_KINDS.contains(sourceKind)) throw new IllegalArgumentException("Invalid relation confirmation source kind");
            checkText(sourceKey, 128, "Invalid relation confirmation source key");
            checkText(batchRef, 128, "Invalid relation confirmation batch reference");
        }
    }
}
