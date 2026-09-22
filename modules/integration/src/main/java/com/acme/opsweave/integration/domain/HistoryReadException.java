package com.acme.opsweave.integration.domain;

/** Stable, non-sensitive failures. Never include source responses or credentials. */
public final class HistoryReadException extends RuntimeException {
    public enum Code {
        SOURCE_UNAVAILABLE, SOURCE_FETCH_FAILED, INVALID_SOURCE_RESPONSE,
        UNSUPPORTED_HISTORY, METADATA_CHANGED, HISTORY_SECOND_LIMIT, HISTORY_BUSY
    }

    private final Code code;

    public HistoryReadException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
