package com.acme.opsweave.integration.domain;

/**
 * Stable sync failure. API responses expose the code and {@link #safeSummary()} only.
 * Exception detail stays in server logs and must not include tokens or raw payloads.
 */
public enum SyncFailureCode {
    SOURCE_SCAN_BUSY("Another source scan owns this scope. No source request was started."),
    SOURCE_SCAN_LOST("Source scan lease was lost. This scan cannot write or reconcile missing objects."),
    SOURCE_SCAN_DEADLINE("Source scan exceeded its five minute deadline. This scan cannot write or reconcile missing objects."),
    SOURCE_SCAN_LIMIT("Host scan fencing counter is exhausted. No source request was started."),
    SOURCE_FETCH_FAILED("Source request failed. Existing entities were kept."),
    RAW_PERSIST_FAILED("Raw record could not be stored. Existing entities were kept."),
    MAPPING_FAILED("A record could not be mapped. Existing entities were kept."),
    INVENTORY_WRITE_FAILED("Entity write failed. Existing entities were kept."),
    CHECKPOINT_FAILED("Sync checkpoint could not be stored. Previously committed inventory changes may remain."),
    PAGE_NOT_ADVANCED("Page cursor did not advance. Existing entities were kept."),
    PAGE_LIMIT_EXCEEDED("Page limit was reached before the snapshot completed. Existing entities were kept."),
    SOURCE_SCAN_UNVERIFIED("The scan ended without a verified snapshot. Existing entities were kept and nothing was reconciled.");

    private final String safeSummary;

    SyncFailureCode(String safeSummary) {
        this.safeSummary = safeSummary;
    }

    public String safeSummary() {
        return safeSummary;
    }

    /** Value stored on the sync run: code plus a fixed summary, never an exception message. */
    public String storedReason() {
        return name() + ": " + safeSummary;
    }

    /**
     * Reads back only the stable code prefix written by {@link #storedReason()}. Any other text,
     * including a future or hand-edited value, yields empty so a trace never echoes raw detail.
     */
    public static java.util.Optional<SyncFailureCode> fromStoredReason(String stored) {
        if (stored == null) {
            return java.util.Optional.empty();
        }
        for (SyncFailureCode code : values()) {
            if (stored.startsWith(code.name() + ": ")) {
                return java.util.Optional.of(code);
            }
        }
        return java.util.Optional.empty();
    }
}
