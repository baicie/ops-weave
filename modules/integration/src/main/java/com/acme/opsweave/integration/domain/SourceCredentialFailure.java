package com.acme.opsweave.integration.domain;

public final class SourceCredentialFailure extends RuntimeException {
    public enum Code { FORBIDDEN, NOT_FOUND, CONFLICT, CAPACITY, UNAVAILABLE }
    private final Code code;
    public SourceCredentialFailure(Code code) { super("Credential operation failed: "+code); this.code=code; }
    public Code code() { return code; }
}
