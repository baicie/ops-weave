package com.acme.opsweave.inventory.domain;

import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;

/** An explicit correction with a new source observation; historical ownership is never rewritten. */
public final class SourceBindingCorrection {
    private SourceBindingCorrection() {}
    public static final int MAX_RECEIPTS = 1000;
    public record Command(UUID requestId, String externalId, UUID expectedSnapshotId,
            EntityId previousEntityId, long expectedPreviousVersion, EntityId targetEntityId, long expectedTargetVersion,
            AssetIdentity.Pin targetIdentity, Instant observedAt, Map<String,String> values, String reason) {
        public Command {
            Objects.requireNonNull(requestId); externalId=SourceReview.bounded(externalId,256); Objects.requireNonNull(expectedSnapshotId);
            Objects.requireNonNull(previousEntityId); Objects.requireNonNull(targetEntityId); Objects.requireNonNull(targetIdentity);
            version(expectedPreviousVersion); version(expectedTargetVersion); Objects.requireNonNull(observedAt);
            if(previousEntityId.equals(targetEntityId) && expectedPreviousVersion!=expectedTargetVersion) throw new IllegalArgumentException("Conflicting entity versions");
            values=SourceReview.fields(values,false); reason=SourceReview.bounded(reason,500);
            new SourceSnapshot.Input(requestId,observedAt,false,List.of(new SourceSnapshot.Row(externalId,targetIdentity.value(),values)));
        }
        public SourceSnapshot.Input input(){return new SourceSnapshot.Input(requestId,observedAt,false,List.of(new SourceSnapshot.Row(externalId,targetIdentity.value(),values)));}
        public void requireCurrent(SourceSnapshot.Presence before,String namespace,long previousVersion,long targetVersion){
            if(!before.entityId().equals(previousEntityId) || !before.externalId().equals(externalId) || !before.snapshotId().equals(expectedSnapshotId)
                    || !before.identity().namespace().equals(namespace) || !targetIdentity.namespace().equals(namespace)
                    || previousVersion!=expectedPreviousVersion || targetVersion!=expectedTargetVersion) throw new Conflict(Code.BINDING_CHANGED);
            if(previousEntityId.equals(targetEntityId) && before.identity().equals(targetIdentity)) throw new Conflict(Code.BINDING_UNCHANGED);
            if(!observedAt.isAfter(before.observedAt())) throw new Conflict(Code.BINDING_CHANGED);
        }
    }
    public record Receipt(TenantId tenantId,String actor,String sourceInstanceId,String namespace,Command command,
            SourceSnapshot.Presence previous,SourceSnapshot.Receipt snapshot,long previousEntityVersionAfter) {
        public Receipt {
            Objects.requireNonNull(tenantId); actor=SourceReview.bounded(actor,128); new SourceScan.Scope(tenantId,sourceInstanceId,"cmdb-host"); AssetIdentity.namespace(namespace);
            Objects.requireNonNull(command);Objects.requireNonNull(previous);Objects.requireNonNull(snapshot);
            command.requireCurrent(previous,namespace,command.expectedPreviousVersion(),command.expectedTargetVersion());
            if(!previous.tenantId().equals(tenantId) || !previous.sourceInstanceId().equals(sourceInstanceId) || !snapshot.tenantId().equals(tenantId)
                    || !snapshot.actor().equals(actor) || !snapshot.sourceInstanceId().equals(sourceInstanceId) || !snapshot.namespace().equals(namespace)
                    || !snapshot.input().equals(command.input()) || snapshot.resolved().size()!=1 || snapshot.markedAbsent()!=0
                    || previousEntityVersionAfter!=command.expectedPreviousVersion()+1) throw new IllegalArgumentException("Correction receipt mismatch");
            var resolved=snapshot.resolved().getFirst();
            if(!resolved.entityId().equals(command.targetEntityId()) || !resolved.identity().equals(command.targetIdentity())
                    || resolved.entityVersion()!=command.expectedTargetVersion()+1) throw new IllegalArgumentException("Correction target mismatch");
        }
    }
    private static void version(long value){if(value<1 || value>=9_007_199_254_740_991L)throw new IllegalArgumentException("Invalid entity version");}
    public enum Code { BINDING_CHANGED, BINDING_UNCHANGED, BINDING_FIELDS_ACTIVE, CORRECTION_REQUEST_CONFLICT, CORRECTION_LIMIT }
    public static final class Conflict extends RuntimeException {
        private final Code code;
        public Conflict(Code code){super(code.name());this.code=code;}
        public Code code(){return code;}
    }
}
