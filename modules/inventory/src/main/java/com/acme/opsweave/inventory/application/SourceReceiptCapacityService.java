package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.inventory.api.SourceReceiptCapacityReader;
import com.acme.opsweave.inventory.domain.SourceReceiptCapacity;
import java.util.List;
import java.util.Objects;

/**
 * Reports how full the stored receipts of the configured import source are. It exists because the
 * caps fail closed: once a source reaches its receipt budget every further write is refused, so the
 * operator needs to see the count coming. This service only counts — it removes nothing and cannot
 * raise a cap.
 */
public final class SourceReceiptCapacityService {
    private final AuthorizationService authorization;
    private final SourceReceiptCapacityReader receipts;
    private final String source;

    public SourceReceiptCapacityService(AuthorizationService authorization, SourceReceiptCapacityReader receipts, String source) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        if (source == null || !source.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid configured import source");
        }
        this.source = source;
    }

    public List<SourceReceiptCapacity> capacity(Principal principal) {
        if (principal == null
            || authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.SOURCE_SYNC).denied()
            || authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.ENTITY_READ).denied()
            || authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.ENTITY_MANAGE).denied()) {
            throw new SourceReviewService.Access(403);
        }
        return List.of(
            new SourceReceiptCapacity(SourceReceiptCapacity.Kind.SNAPSHOT, source,
                receipts.snapshotReceipts(principal.tenantId(), source), SourceReceiptCapacity.cap(SourceReceiptCapacity.Kind.SNAPSHOT)),
            new SourceReceiptCapacity(SourceReceiptCapacity.Kind.BINDING_CORRECTION, source,
                receipts.correctionReceipts(principal.tenantId(), source), SourceReceiptCapacity.cap(SourceReceiptCapacity.Kind.BINDING_CORRECTION))
        );
    }

    public String source() {
        return source;
    }
}
