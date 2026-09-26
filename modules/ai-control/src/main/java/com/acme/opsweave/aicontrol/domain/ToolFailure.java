package com.acme.opsweave.aicontrol.domain;

public final class ToolFailure extends RuntimeException {
    public enum Code { INVALID_REQUEST, FORBIDDEN, NOT_FOUND, EXPIRED, BUDGET_EXHAUSTED, BUSY, INPUT_CHANGED, READ_LIMIT, UNAVAILABLE, DEADLINE }
    private final Code code;
    public ToolFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
