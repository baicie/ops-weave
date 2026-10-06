package com.acme.opsweave.integration.application;

import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.WorkflowLogStream;

/** Reads certainty evidence without changing the original batch journal. */
final class WorkflowLogOutcome {
    private WorkflowLogOutcome() {}

    static boolean legacyUncertain(WorkflowStore.Session session, String owner, WorkflowLogStream.Batch batch) {
        return batch.state().equals("FAILED") && !session.logStreamRejectionKnown(owner, batch.id());
    }

    static boolean uncertain(WorkflowStore.Session session, String owner, WorkflowLogStream.Batch batch) {
        return batch.state().equals("UNKNOWN") || legacyUncertain(session, owner, batch);
    }
}
