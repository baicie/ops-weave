package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.*;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.*;
import java.util.*;

/** Controlled relation instance maintenance. Identity and tenant always come from Principal. */
public final class EntityRelationService {
    private static final int PAGE_SCAN_BUDGET = 1000;
    private static final int PAGE_SCAN_CHUNK = 50;
    private final AuthorizationService authorization; private final InventoryQuery entities;
    private final RelationStore relations; private final RelationModelReader models; private final Clock clock;
    public EntityRelationService(AuthorizationService authorization, InventoryQuery entities, RelationStore relations, RelationModelReader models, Clock clock) {
        this.authorization=Objects.requireNonNull(authorization);this.entities=Objects.requireNonNull(entities);this.relations=Objects.requireNonNull(relations);this.models=Objects.requireNonNull(models);this.clock=Objects.requireNonNull(clock);
    }
    public RelationStore.WriteResult write(Principal p, UUID requestId, String relationType, int relationRevision, EntityId from, EntityId to,
            Instant validFrom, Instant validTo, String sourceRef, String dataMode, long expectedFromVersion, long expectedToVersion) {
        authorize(p, from); authorize(p, to); if (requestId == null) throw new IllegalArgumentException("Invalid request id");
        var model=models.find(p.tenantId(),relationType,relationRevision).orElseThrow(()->new RelationStore.Conflict("Relation model is not published"));
        var left=entities.find(p.tenantId(),from).orElseThrow(()->new RelationStore.Conflict("From entity missing"));
        var right=entities.find(p.tenantId(),to).orElseThrow(()->new RelationStore.Conflict("To entity missing"));
        if(left.version()!=expectedFromVersion||right.version()!=expectedToVersion)throw new RelationStore.Conflict("Entity version changed");
        if(!typeMatches(left.type(),model.fromType())||!typeMatches(right.type(),model.toType()))throw new RelationStore.Conflict("Relation endpoints do not match model");
        var at=validFrom==null?clock.instant():validFrom;if(at.isAfter(clock.instant().plus(Duration.ofMinutes(1))))throw new IllegalArgumentException("Relation starts in the future");
        var relation=new EntityRelation(requestId,p.tenantId(),from,relationType,relationRevision,to,at,validTo,sourceRef,dataMode,1);
        return relations.write(p.tenantId(),requestId,relation,expectedFromVersion,expectedToVersion);
    }
    public List<EntityRelation> page(Principal p,EntityId endpoint,UUID after,Instant asOf,int limit){
        readAuthorize(p,endpoint); entities.find(p.tenantId(),endpoint).orElseThrow(()->new RelationStore.NotFound("Entity missing"));
        if(limit<1||limit>50)throw new IllegalArgumentException("Invalid relation limit");
        var cutoff=asOf==null?clock.instant():asOf;var visibleRows=new ArrayList<EntityRelation>(limit+1);var cursor=after;int scanned=0;boolean more=false;
        while(scanned<PAGE_SCAN_BUDGET){
            int chunk=Math.min(PAGE_SCAN_CHUNK,PAGE_SCAN_BUDGET-scanned);var rows=relations.page(p.tenantId(),endpoint,cursor,cutoff,chunk);
            if(rows.size()>chunk+1)throw new IllegalStateException("Relation store exceeded page bound");
            int count=Math.min(rows.size(),chunk);
            for(int i=0;i<count;i++){
                var row=rows.get(i);cursor=row.id();scanned++;
                if(visible(p,row.fromEntityId())&&visible(p,row.toEntityId()))visibleRows.add(row);
                if(visibleRows.size()>limit)return List.copyOf(visibleRows);
            }
            more=rows.size()>chunk;
            if(!more)return List.copyOf(visibleRows);
        }
        if(more)throw new RelationStore.ScanBudgetExceeded();
        return List.copyOf(visibleRows);
    }
    private void authorize(Principal p,EntityId id){if(!p.has(Permission.ENTITY_MANAGE)||authorization.authorize(p,ResourceRef.entity(p.tenantId(),id),Permission.ENTITY_MANAGE).denied())throw new RelationStore.Access("Relation management denied");}
    private void readAuthorize(Principal p,EntityId id){if(!p.has(Permission.ENTITY_READ)||authorization.authorize(p,ResourceRef.entity(p.tenantId(),id),Permission.ENTITY_READ).denied())throw new RelationStore.Access("Relation read denied");}
    private boolean visible(Principal p,EntityId id){return authorization.authorize(p,ResourceRef.entity(p.tenantId(),id),Permission.ENTITY_READ).allowed();}
    private static boolean typeMatches(String actual,String expected){
        if(actual==null||expected==null)return false;
        if(expected.startsWith("builtin."))return builtinTypeKey(actual).equals(builtinTypeKey(expected.substring("builtin.".length())));
        return actual.equalsIgnoreCase(expected);
    }
    private static String builtinTypeKey(String value){String unqualified=value.startsWith("builtin.")?value.substring("builtin.".length()):value;return unqualified.replace("_","").toLowerCase(Locale.ROOT);}
}
