package com.acme.opsweave.telemetry.api;

/** Storage could not answer. This is not an empty series. */
public final class MetricQueryException extends RuntimeException {
    public enum Code { SOURCE_UNAVAILABLE, INVALID_RESPONSE }

    private final Code code;

    public MetricQueryException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
