package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.SourceReviewStore;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

/** Every read and retry authorizes the trusted subject before reading review or receipt data. */
public final class SourceReviewService {
    private final AuthorizationService authorization;
    private final GetEntityUseCase entities;
    private final SourceReviewStore store;
    private final Clock clock;
    private final String sourceId;
    public SourceReviewService(AuthorizationService authorization, GetEntityUseCase entities, SourceReviewStore store, Clock clock, String sourceId) {
        this.authorization = authorization; this.entities = entities; this.store = store; this.clock = clock;
        if (sourceId == null || (!sourceId.isEmpty() && !sourceId.matches("[a-zA-Z0-9_.:-]{1,128}"))) throw new IllegalArgumentException("Invalid configured import source");
        this.sourceId = sourceId;
    }
    public void authorize(Principal p, EntityId id) {
        if (authorization.authorize(p, ResourceRef.entity(p.tenantId(), id), Permission.ENTITY_READ).denied()
                || authorization.authorize(p, ResourceRef.entity(p.tenantId(), id), Permission.ENTITY_MANAGE).denied()) throw new Access(403);
        if (sourceId.isEmpty()) throw new Access(503);
        if (authorization.authorize(p, ResourceRef.source(p.tenantId(), sourceId), Permission.SOURCE_SYNC).denied()) throw new Access(403);
        if (entities.get(p, id).kind() != EntityAccessKind.FOUND) throw new Access(404);
    }
    public String sourceId() { return sourceId; }
    public SourceReview stage(Principal p, EntityId id, UUID requestId, long entityVersion, String externalId, Instant observedAt, Map<String,String> fields, String mappingDigest) {
        return stage(p,id,requestId,entityVersion,externalId,observedAt,fields,mappingDigest,null);
    }
    public SourceReview stage(Principal p, EntityId id, UUID requestId, long entityVersion, String externalId, Instant observedAt, Map<String,String> fields, String mappingDigest, AssetIdentity.Pin identity) {
        authorize(p,id);
        return store.stage(p.tenantId(), id, new SourceReviewStore.Import(requestId, entityVersion,
            new ExternalObjectKey(p.tenantId(), sourceId, "cmdb-host", externalId, "1"), observedAt, fields, mappingDigest, p.subjectId().value(),identity), clock.instant());
    }
    public SourceReview decide(Principal p, EntityId id, UUID reviewId, UUID requestId, SourceReview.Action action, long entityVersion, int reviewVersion, Map<String,SourceReview.Choice> choices, String reason) {
        authorize(p,id);
        return store.decide(p.tenantId(), id, sourceId, reviewId, new SourceReview.Command(requestId, action, entityVersion, reviewVersion, choices, reason, p.subjectId().value()), clock.instant());
    }
    public SourceReviewStore.Page page(Principal p, EntityId id, UUID after, int limit) { authorize(p,id); return store.reviews(p.tenantId(), id, sourceId, after, limit); }
    public static final class Access extends RuntimeException { private final int status; public Access(int status) { this.status = status; } public int status() { return status; } }
}
