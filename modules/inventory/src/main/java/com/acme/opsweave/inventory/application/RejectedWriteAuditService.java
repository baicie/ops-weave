package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.inventory.api.RejectedWriteAttemptStore;
import com.acme.opsweave.inventory.domain.RejectedWriteAttempt;
import java.util.List;
import java.util.Objects;

/**
 * Read-only view of refused supplemental-source writes for the configured import source. It never
 * replays a refused request, never authorizes a write and never returns field values: an operator
 * sees which operation was refused, under which stable code, by whom, and which fields were attempted.
 */
public final class RejectedWriteAuditService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = RejectedWriteAttemptStore.MAX_RECENT;

    private final AuthorizationService authorization;
    private final RejectedWriteAttemptStore audit;
    private final String source;

    public RejectedWriteAuditService(AuthorizationService authorization, RejectedWriteAttemptStore audit, String source) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (source == null || !source.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid configured import source");
        }
        this.source = source;
    }

    public List<RejectedWriteAttempt> recent(Principal principal, int limit) {
        if (principal == null
            || authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.SOURCE_SYNC).denied()
            || authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.ENTITY_READ).denied()
            || authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.ENTITY_MANAGE).denied()) {
            throw new SourceReviewService.Access(403);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid audit limit");
        }
        return audit.recent(principal.tenantId(), source, limit);
    }

    public String source() {
        return source;
    }
}
