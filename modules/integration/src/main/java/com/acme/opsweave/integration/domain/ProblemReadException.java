package com.acme.opsweave.integration.domain;

/** Safe boundary codes; never propagate vendor payloads, credentials or exception text. */
public final class ProblemReadException extends RuntimeException {
    public enum Code { FORBIDDEN, SOURCE_UNAVAILABLE, SOURCE_FETCH_FAILED, INVALID_SOURCE_RESPONSE, SOURCE_BUSY }
    private final Code code;
    public ProblemReadException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
