package com.acme.opsweave.incident.domain;

public final class IncidentFailure extends RuntimeException {
    public enum Code { FORBIDDEN, NOT_FOUND, INVALID_REQUEST, CONFLICT, INVALID_TRANSITION, ACTIVE_PROBLEMS, READ_LIMIT, UNAVAILABLE }
    private final Code code;
    public IncidentFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
