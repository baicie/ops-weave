package com.acme.opsweave.inventory.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.api.*;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.domain.RelationCardinality;
import com.acme.opsweave.inventory.domain.RelationConfirmation;
import com.acme.opsweave.inventory.domain.RelationCorrection;
import com.acme.opsweave.sharedkernel.EntityId;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;

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
        return relations.write(p.tenantId(),requestId,relation,expectedFromVersion,expectedToVersion,cardinality(model));
    }
    public RelationStore.WriteResult close(Principal p, UUID requestId, UUID relationId, Instant validTo, String reason, long expectedVersion) {
        if(requestId == null || relationId == null)throw new IllegalArgumentException("Invalid relation close");
        checkReason(reason, "Invalid relation close reason");
        if(expectedVersion < 1)throw new IllegalArgumentException("Invalid relation version");
        var existing=relations.find(p.tenantId(),relationId).orElseThrow(()->new RelationStore.NotFound("Relation missing"));
        authorize(p,existing.fromEntityId());authorize(p,existing.toEntityId());
        var at=validTo==null?clock.instant():validTo;
        if(!at.isAfter(existing.validFrom()))throw new IllegalArgumentException("Relation closes before it starts");
        if(at.isAfter(clock.instant().plus(Duration.ofMinutes(1))))throw new IllegalArgumentException("Relation closes in the future");
        return relations.close(p.tenantId(),requestId,relationId,at,reason,expectedVersion);
    }
    /** Explicit correction record: the previous row keeps its endpoints, type, source annotation and
     *  history and only receives its effective end time; the corrected values are a new relation
     *  record linked to it. Correcting a terminated relation is rejected, never a revival. */
    public RelationStore.WriteResult correct(Principal p, UUID requestId, UUID relationId, String reason, long expectedVersion,
            EntityId from, EntityId to, String relationType, int relationRevision) {
        if (requestId == null || relationId == null) throw new IllegalArgumentException("Invalid relation correction");
        checkReason(reason, "Invalid relation correction reason");
        if (expectedVersion < 1) throw new IllegalArgumentException("Invalid relation version");
        var existing = relations.find(p.tenantId(), relationId).orElseThrow(() -> new RelationStore.NotFound("Relation missing"));
        authorize(p, existing.fromEntityId()); authorize(p, existing.toEntityId());
        authorize(p, from); authorize(p, to);
        if (existing.fromEntityId().equals(from) && existing.toEntityId().equals(to)
            && existing.relationType().equals(relationType) && existing.relationRevision() == relationRevision)
            throw new IllegalArgumentException("Relation correction changes nothing");
        var model = models.find(p.tenantId(), relationType, relationRevision).orElseThrow(() -> new RelationStore.CorrectionConflict("Relation model is not published"));
        var left = entities.find(p.tenantId(), from).orElseThrow(() -> new RelationStore.CorrectionConflict("From entity missing"));
        var right = entities.find(p.tenantId(), to).orElseThrow(() -> new RelationStore.CorrectionConflict("To entity missing"));
        if (!typeMatches(left.type(), model.fromType()) || !typeMatches(right.type(), model.toType()))
            throw new RelationStore.CorrectionConflict("Relation endpoints do not match model");
        var at = clock.instant();
        if (!at.isAfter(existing.validFrom())) throw new IllegalArgumentException("Relation closes before it starts");
        var replacement = new EntityRelation(UUID.randomUUID(), p.tenantId(), from, relationType, relationRevision, to,
            at, null, existing.sourceRef(), existing.dataMode(), 1);
        return relations.correct(p.tenantId(), requestId, relationId, reason, expectedVersion, replacement, correctionCardinality(model));
    }
    /** One independent confirmation entry on the single relation record; entries never create extra
     *  relation instances and never rewrite each other. */
    public RelationStore.ConfirmationResult confirm(Principal p, UUID requestId, UUID relationId, String sourceKind, String sourceKey, String batchRef, String dataMode) {
        if (requestId == null || relationId == null) throw new IllegalArgumentException("Invalid relation confirmation");
        var entry = new RelationConfirmation(sourceKind, sourceKey, batchRef, dataMode, clock.instant(), null, null);
        var existing = relations.find(p.tenantId(), relationId).orElseThrow(() -> new RelationStore.NotFound("Relation missing"));
        authorize(p, existing.fromEntityId()); authorize(p, existing.toEntityId());
        return relations.confirm(p.tenantId(), requestId, relationId, entry);
    }
    /** Withdraw exactly one confirmation entry. Source failure never deletes relations or entries. */
    public RelationStore.ConfirmationResult withdraw(Principal p, UUID requestId, UUID relationId, String sourceKind, String sourceKey, String batchRef, String reason) {
        if (requestId == null || relationId == null) throw new IllegalArgumentException("Invalid relation confirmation withdrawal");
        checkReason(reason, "Invalid relation confirmation withdrawal reason");
        var key = new RelationConfirmation.Key(sourceKind, sourceKey, batchRef);
        var existing = relations.find(p.tenantId(), relationId).orElseThrow(() -> new RelationStore.NotFound("Relation missing"));
        authorize(p, existing.fromEntityId()); authorize(p, existing.toEntityId());
        var at = clock.instant();
        if (existing.validTo() == null && !at.isAfter(existing.validFrom())) throw new IllegalArgumentException("Relation closes before it starts");
        return relations.withdraw(p.tenantId(), requestId, relationId, key, at, reason);
    }
    public record ConfirmationView(EntityRelation relation, List<RelationConfirmation> entries) {
        public ConfirmationView { entries = List.copyOf(entries); }
    }
    public ConfirmationView confirmations(Principal p, UUID relationId) {
        if (relationId == null) throw new IllegalArgumentException("Invalid relation confirmation view");
        var existing = relations.find(p.tenantId(), relationId).orElseThrow(() -> new RelationStore.NotFound("Relation missing"));
        readAuthorize(p, existing.fromEntityId()); readAuthorize(p, existing.toEntityId());
        return new ConfirmationView(existing, relations.confirmations(p.tenantId(), relationId));
    }
    public List<RelationCorrection> corrections(Principal p, EntityId endpoint, UUID after, int limit) {
        readAuthorize(p, endpoint); entities.find(p.tenantId(), endpoint).orElseThrow(() -> new RelationStore.NotFound("Entity missing"));
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid relation limit");
        var visibleRows = new ArrayList<RelationCorrection>(limit + 1); var cursor = after; int scanned = 0; boolean more = false;
        while (scanned < PAGE_SCAN_BUDGET) {
            int chunk = Math.min(PAGE_SCAN_CHUNK, PAGE_SCAN_BUDGET - scanned);
            var rows = relations.corrections(p.tenantId(), endpoint, cursor, chunk);
            if (rows.size() > chunk + 1) throw new IllegalStateException("Relation store exceeded page bound");
            int count = Math.min(rows.size(), chunk);
            for (int i = 0; i < count; i++) {
                var row = rows.get(i); cursor = row.requestId(); scanned++;
                if (correctionVisible(p, row)) visibleRows.add(row);
                if (visibleRows.size() > limit) return List.copyOf(visibleRows);
            }
            more = rows.size() > chunk;
            if (!more) return List.copyOf(visibleRows);
        }
        if (more) throw new RelationStore.ScanBudgetExceeded();
        return List.copyOf(visibleRows);
    }
    private boolean correctionVisible(Principal p, RelationCorrection record) {
        var previous = relations.find(p.tenantId(), record.previousRelationId());
        var replacement = relations.find(p.tenantId(), record.relationId());
        if (previous.isEmpty() || replacement.isEmpty()) return false;
        return visible(p, previous.get().fromEntityId()) && visible(p, previous.get().toEntityId())
            && visible(p, replacement.get().fromEntityId()) && visible(p, replacement.get().toEntityId());
    }
    private static void checkReason(String reason, String message) {
        if (reason == null || reason.isBlank() || reason.length() > 256 || reason.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(message);
    }
    public List<EntityRelation> page(Principal p,EntityId endpoint,UUID after,Instant asOf,int limit){
        readAuthorize(p,endpoint); entities.find(p.tenantId(),endpoint).orElseThrow(()->new RelationStore.NotFound("Entity missing"));
        if(limit<1||limit>50)throw new IllegalArgumentException("Invalid relation limit");
        var cutoff=asOf==null?clock.instant():asOf;
        return visiblePage(p,endpoint,after,limit,(cursor,chunk)->relations.page(p.tenantId(),endpoint,cursor,cutoff,chunk));
    }
    public List<EntityRelation> history(Principal p,EntityId endpoint,UUID after,int limit){
        readAuthorize(p,endpoint); entities.find(p.tenantId(),endpoint).orElseThrow(()->new RelationStore.NotFound("Entity missing"));
        if(limit<1||limit>50)throw new IllegalArgumentException("Invalid relation limit");
        return visiblePage(p,endpoint,after,limit,(cursor,chunk)->relations.history(p.tenantId(),endpoint,cursor,chunk));
    }
    private List<EntityRelation> visiblePage(Principal p,EntityId endpoint,UUID after,int limit,BiFunction<UUID,Integer,List<EntityRelation>> fetch){
        var visibleRows=new ArrayList<EntityRelation>(limit+1);var cursor=after;int scanned=0;boolean more=false;
        while(scanned<PAGE_SCAN_BUDGET){
            int chunk=Math.min(PAGE_SCAN_CHUNK,PAGE_SCAN_BUDGET-scanned);var rows=fetch.apply(cursor,chunk);
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
    private static RelationCardinality cardinality(RelationModelReader.Definition model){
        try{return RelationCardinality.fromModel(model.cardinality());}catch(IllegalArgumentException unknown){throw new RelationStore.Conflict("Unsupported relation cardinality");}
    }
    private static RelationCardinality correctionCardinality(RelationModelReader.Definition model){
        try{return RelationCardinality.fromModel(model.cardinality());}catch(IllegalArgumentException unknown){throw new RelationStore.CorrectionConflict("Unsupported relation cardinality");}
    }
    private static boolean typeMatches(String actual,String expected){
        if(actual==null||expected==null)return false;
        if(expected.startsWith("builtin."))return builtinTypeKey(actual).equals(builtinTypeKey(expected.substring("builtin.".length())));
        return actual.equalsIgnoreCase(expected);
    }
    private static String builtinTypeKey(String value){String unqualified=value.startsWith("builtin.")?value.substring("builtin.".length()):value;return unqualified.replace("_","").toLowerCase(Locale.ROOT);}
}
