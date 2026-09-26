package com.acme.opsweave.integration.domain;

/** Safe codes only; never expose a retained payload or database exception. */
public final class PipelineException extends RuntimeException {
    public enum Code { FORBIDDEN, NOT_FOUND, VERSION_CONFLICT, DIGEST_MISMATCH, RUN_NOT_READY,
        LINEAGE_UNAVAILABLE, INVALID_REQUEST, REPLAY_BUSY, REPLAY_KEY_CONFLICT, REPLAY_LEASE_LOST, REPLAY_RESULT_TOO_LARGE, DRAFT_CONFLICT }
    private final Code code;
    public PipelineException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
