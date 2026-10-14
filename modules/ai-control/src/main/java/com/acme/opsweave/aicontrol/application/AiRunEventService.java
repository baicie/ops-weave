package com.acme.opsweave.aicontrol.application;

import com.acme.opsweave.aicontrol.api.AiRunStore;
import com.acme.opsweave.aicontrol.domain.AiRunEvent;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.domain.Principal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The controlled readback of one run's ordered event log (M5 5c, ADR-104 §2.5).
 *
 * <p>Reading is a pure projection over persisted records: it never executes a tool, never calls a
 * model, never advances state and never sends a notification, so re-reading the same cursor
 * (replay, reconnect, poll) always answers with the same records and zero side effects. The cursor
 * is the last seen {@code seq}; a cursor below the retained window has expired and the answer is the
 * retained window plus {@code gap=true} and the current run snapshot — never a fake continuous
 * history.
 *
 * <p>Authorization is exactly the run read boundary: the caller's current authority over the
 * incident named by the run is re-checked for every page, and the events of a run the caller cannot
 * read are a 404, not a guessed position.
 */
public final class AiRunEventService {
    private final AiRunService runs;
    private final AiRunStore store;

    public AiRunEventService(AiRunService runs, AiRunStore store) {
        this.runs = Objects.requireNonNull(runs);
        this.store = Objects.requireNonNull(store);
    }

    /**
     * One bounded page of the retained ordering {@code seq ASC}, strictly after the cursor. The
     * empty tail page is a complete result; {@code hasMore} and {@code nextCursor} are consistent.
     */
    public AiRunEvent.Page read(Principal p, UUID runId, String afterSeq, Integer limit) {
        Objects.requireNonNull(p, "principal");
        Objects.requireNonNull(runId, "runId");
        var run = runs.get(p, runId);
        long cursor = AiRunEvent.cursor(afterSeq);
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 50) throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);
        List<AiRunEvent.Event> rows = store.read(p.tenantId(), runId, cursor, size + 1);
        return AiRunEvent.Page.of(runId, rows, cursor, size, store.bounds(p.tenantId(), runId),
            AiRunEvent.Snapshot.of(run));
    }
}
