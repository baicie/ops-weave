package com.acme.opsweave.catalog.domain;

public final class CatalogFailure extends RuntimeException {
    public enum Code { FORBIDDEN, NOT_FOUND, CONFLICT, INCOMPATIBLE_REVISION, UNKNOWN_ENTITY_TYPE, UNAVAILABLE }
    private final Code code;
    public CatalogFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
