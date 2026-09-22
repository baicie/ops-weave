package com.acme.opsweave.integration.domain;

/**
 * Stable sync failure. API responses expose the code and {@link #safeSummary()} only.
 * Exception detail stays in server logs and must not include tokens or raw payloads.
 */
public enum SyncFailureCode {
    SOURCE_FETCH_FAILED("Source request failed. Existing entities were kept."),
    RAW_PERSIST_FAILED("Raw record could not be stored. Existing entities were kept."),
    MAPPING_FAILED("A record could not be mapped. Existing entities were kept."),
    INVENTORY_WRITE_FAILED("Entity write failed. Existing entities were kept."),
    CHECKPOINT_FAILED("Sync checkpoint could not be stored. Existing entities were kept."),
    PAGE_NOT_ADVANCED("Page cursor did not advance. Existing entities were kept."),
    PAGE_LIMIT_EXCEEDED("Page limit was reached before the snapshot completed. Existing entities were kept.");

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
}
