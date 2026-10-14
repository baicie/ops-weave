package com.acme.opsweave.aicontrol.api;

import com.acme.opsweave.aicontrol.domain.AiRunEvent;
import com.acme.opsweave.sharedkernel.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Storage for the ordered run event log (ADR-104 §2.5). Events are records: appending one only
 * records a fact that already happened, and reading never appends, executes or advances anything.
 *
 * <p>The registry store writes its lifecycle events at the state transition points themselves (the
 * submission, the claim, each applied move, the recorded cancellation request) so an event and the
 * state it describes land together; this port adds the explicit disclosure record and the readback.
 * Sequence numbers are per run and strictly monotonic; the retained window is server-defined and a
 * cursor below it expires into an explicit gap instead of a fake continuous history.
 */
public interface AiRunEventStore {
    /**
     * Append one record and assign the next sequence number of this run atomically. The caller names
     * the fact that already happened; nothing is executed because a record exists.
     */
    AiRunEvent.Event append(TenantId tenant, UUID runId, AiRunEvent.Type type, Instant occurredAt, AiRunEvent.Payload payload);

    /**
     * At most {@code limit} retained events of this run strictly after the cursor, ordered by {@code
     * seq ASC}. Reading is side-effect free: the same cursor always answers with the same records.
     */
    List<AiRunEvent.Event> read(TenantId tenant, UUID runId, long afterSeq, int limit);

    /** The retained sequence bounds of this run's event log; both are 0 when nothing is retained. */
    AiRunEvent.Bounds bounds(TenantId tenant, UUID runId);
}
