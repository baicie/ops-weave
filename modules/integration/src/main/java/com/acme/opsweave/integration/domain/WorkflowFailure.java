package com.acme.opsweave.integration.domain;
public final class WorkflowFailure extends RuntimeException {
 public enum Code { FORBIDDEN, NOT_FOUND, CONFLICT, MODEL_CHANGED, MAPPING_CHANGED, PREVIEW_REQUIRED, SOURCE_UNAVAILABLE, SOURCE_CHANGED, BUSY, CAPACITY, INVALID_SAMPLE, WINDOW_INCOMPLETE, SOURCE_WINDOW_CHANGED, OPERATOR_CHANGED, OPERATOR_PIN_REQUIRED, AUTHORIZATION_EXPIRED, AUTHORIZATION_REVOKED, EXECUTION_LIMIT }
 private final Code code;
 private final WorkflowDiagnostics.Result diagnostics;
 private final WorkflowDiagnostics.SourceFailure sourceFailure;
 public WorkflowFailure(Code code) { this(code,null); }
 public WorkflowFailure(Code code,WorkflowDiagnostics.Result diagnostics) { this(code,diagnostics,null); }
 private WorkflowFailure(Code code,WorkflowDiagnostics.Result diagnostics,WorkflowDiagnostics.SourceFailure sourceFailure) { super(code.name()); this.code=code;this.diagnostics=diagnostics;this.sourceFailure=sourceFailure; }
 public static WorkflowFailure sourceChanged(WorkflowDiagnostics.SourceFailure reason){if(!java.util.Set.of(WorkflowDiagnostics.SourceFailure.METADATA_CHANGED,WorkflowDiagnostics.SourceFailure.SOURCE_KEY_CHANGED,WorkflowDiagnostics.SourceFailure.VALUE_TYPE_CHANGED,WorkflowDiagnostics.SourceFailure.UNIT_CHANGED).contains(reason))throw new IllegalArgumentException();return new WorkflowFailure(Code.SOURCE_CHANGED,null,reason);}
 public Code code() { return code; }
 public WorkflowDiagnostics.Result diagnostics(){return diagnostics;}
 public WorkflowDiagnostics.SourceFailure sourceFailure(){return sourceFailure;}
}
