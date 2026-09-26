package com.acme.opsweave.integration.domain;

/** Safe codes only; never expose a stored payload, lease token or database exception. */
public final class SourceScanRunException extends RuntimeException {
    public enum Code { FORBIDDEN, NOT_FOUND, INVALID_REQUEST, UNCONFIGURED }

    private final Code code;

    public SourceScanRunException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
