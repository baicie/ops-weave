package com.acme.opsweave.integration.api;

import com.acme.opsweave.integration.domain.WorkflowLogOutput;
import com.acme.opsweave.integration.domain.WorkflowLogWindow;
import java.util.List;

/** One whole-window attempt and scoped readback; no retry or DDL. */
public interface WorkflowLogWindowSink {
    boolean ready();
    void write(WorkflowLogWindow.Batch batch);
    List<WorkflowLogWindow.Record> read(WorkflowLogOutput.Scope scope);
}
