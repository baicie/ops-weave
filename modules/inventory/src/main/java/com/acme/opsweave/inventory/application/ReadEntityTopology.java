package com.acme.opsweave.inventory.application;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.TopologyReader;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.Clock;
import java.util.*;
public final class ReadEntityTopology {
    private final AuthorizationService authorization; private final TopologyReader reader; private final Clock clock;
    public ReadEntityTopology(AuthorizationService authorization, TopologyReader reader, Clock clock) { this.authorization=authorization; this.reader=reader; this.clock=clock; }
    public Optional<EntityTopology> read(Principal principal, EntityId center) {
        if (!principal.has(Permission.ENTITY_READ) || authorization.authorize(principal,ResourceRef.entity(principal.tenantId(),center),Permission.ENTITY_READ).denied()) return Optional.empty();
        boolean all=authorization.authorize(principal,ResourceRef.anyEntity(principal.tenantId()),Permission.ENTITY_READ).allowed();
        var ids=new HashSet<EntityId>();
        if (!all) for (var ref:principal.resourceScope().allowedResources()) {
            if (ref.tenantId().equals(principal.tenantId()) && ref.type().equals("entity") && !ref.id().equals("*") && authorization.authorize(principal,ref,Permission.ENTITY_READ).allowed()) ids.add(EntityId.parse(ref.id()));
        }
        var scope=new EntityVisibility(all,ids); var at=clock.instant();
        var result=reader.read(principal.tenantId(),center,scope,at);
        result.ifPresent(view -> {
            if (!view.tenant().equals(principal.tenantId()) || !view.center().equals(center) || !view.asOf().equals(at)) throw new IllegalStateException("Topology scope violation");
            for (var node:view.nodes()) if (!scope.includes(node.id()) || authorization.authorize(principal,ResourceRef.entity(principal.tenantId(),node.id()),Permission.ENTITY_READ).denied()) throw new IllegalStateException("Topology object scope violation");
        });
        return result;
    }
}
