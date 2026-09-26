package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.AssetIdentityStore;
import com.acme.opsweave.inventory.domain.AssetIdentity;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Clock;
import java.util.*;

/** Uses the operator-configured namespace/source and fresh resource authorization on every lookup or retry. */
public final class AssetIdentityService {
    private final AuthorizationService authorization; private final GetEntityUseCase entities; private final AssetIdentityStore store;
    private final String namespace,source; private final Clock clock;
    public AssetIdentityService(AuthorizationService authorization,GetEntityUseCase entities,AssetIdentityStore store,String namespace,String source,Clock clock) {
        this.authorization=authorization; this.entities=entities; this.store=store; this.namespace=namespace; this.source=source; this.clock=clock;
        if(namespace==null || source==null) throw new IllegalArgumentException("Invalid identity configuration");
        if(!namespace.isEmpty()) AssetIdentity.namespace(namespace);
        if(!source.isEmpty() && !source.matches("[a-zA-Z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid identity source");
    }
    public String namespace() { return namespace; }
    private void base(Principal p) {
        if(!p.permissions().containsAll(Set.of(Permission.ENTITY_READ,Permission.ENTITY_MANAGE,Permission.SOURCE_SYNC))) throw new SourceReviewService.Access(403);
        if(namespace.isEmpty() || source.isEmpty()) throw new SourceReviewService.Access(503);
        if(authorization.authorize(p,ResourceRef.source(p.tenantId(),source),Permission.SOURCE_SYNC).denied()) throw new SourceReviewService.Access(403);
    }
    public void authorize(Principal p,EntityId id) {
        base(p);
        if(authorization.authorize(p,ResourceRef.entity(p.tenantId(),id),Permission.ENTITY_READ).denied()
            || authorization.authorize(p,ResourceRef.entity(p.tenantId(),id),Permission.ENTITY_MANAGE).denied()) throw new SourceReviewService.Access(403);
        if(entities.get(p,id).kind()!=EntityAccessKind.FOUND) throw new SourceReviewService.Access(404);
    }
    public List<AssetIdentity> page(Principal p,EntityId entity,UUID after,int limit) { authorize(p,entity); return store.identities(p.tenantId(),entity,namespace,after,limit); }
    public AssetIdentityStore.Receipt change(Principal p,EntityId entity,UUID requestId,AssetIdentity.Action action,UUID identityId,long version,String value,String reason) {
        authorize(p,entity); return store.change(p.tenantId(),entity,namespace,new AssetIdentity.Command(requestId,action,identityId,version,value,p.subjectId().value(),reason),clock.instant());
    }
    public AssetIdentityStore.Match resolve(Principal p,String value) {
        base(p); AssetIdentity.value(value); var found=store.resolve(p.tenantId(),namespace,value).orElseThrow(() -> new SourceReviewService.Access(404));
        var resource=ResourceRef.entity(p.tenantId(),found.identity().entityId());
        if(authorization.authorize(p,resource,Permission.ENTITY_READ).denied() || authorization.authorize(p,resource,Permission.ENTITY_MANAGE).denied()
            || entities.get(p,found.identity().entityId()).kind()!=EntityAccessKind.FOUND) throw new SourceReviewService.Access(404);
        return found;
    }
}
