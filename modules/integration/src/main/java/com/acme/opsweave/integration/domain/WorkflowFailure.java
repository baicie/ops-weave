package com.acme.opsweave.integration.domain;
public final class WorkflowFailure extends RuntimeException {
 public enum Code { FORBIDDEN, NOT_FOUND, CONFLICT, MODEL_CHANGED, PREVIEW_REQUIRED, SOURCE_UNAVAILABLE, BUSY, CAPACITY, INVALID_SAMPLE }
 private final Code code;
 public WorkflowFailure(Code code) { super(code.name()); this.code=code; }
 public Code code() { return code; }
}
