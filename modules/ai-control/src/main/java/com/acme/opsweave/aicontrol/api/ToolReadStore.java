package com.acme.opsweave.aicontrol.api;

import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

public interface ToolReadStore {
    /** At most four unexpired read sessions per tenant/subject. */
    void create(ToolReadSession session);
    /** Atomically consumes the shared session budget and starts an audit row, after checking owner and deadline. */
    Call begin(TenantId tenant, SubjectId subject, UUID session, String tool, Instant now);
    /** Snapshot and audit complete together; evidence.get records the existing snapshot ID without reinserting it. */
    void complete(Call call, PlatformEvidence evidence, int bytes, Instant now);
    void fail(Call call, ToolFailure.Code code, Instant now);
    Optional<PlatformEvidence> find(TenantId tenant, UUID evidenceId);
    default Optional<AiRetention.Marker> retired(TenantId tenant, UUID evidenceId) { return Optional.empty(); }
    Optional<ToolReadSession> session(TenantId tenant, SubjectId subject, UUID sessionId);
    boolean rechecked(TenantId tenant, SubjectId subject, UUID sessionId, Set<UUID> evidenceIds);
    void auditAccess(TenantId tenant, SubjectId subject, UUID evidenceId, String outcome, int bytes, Instant now);
    record Call(UUID id, ToolReadSession session, String tool, Instant startedAt) {}
}
