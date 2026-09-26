package com.acme.opsweave.integration.domain;

/** Safe codes only; never expose a vendor message, endpoint or credential. */
public final class SourceConnectionCheckException extends RuntimeException {
    public enum Code { FORBIDDEN, INVALID_REQUEST, UNCONFIGURED }

    private final Code code;

    public SourceConnectionCheckException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
