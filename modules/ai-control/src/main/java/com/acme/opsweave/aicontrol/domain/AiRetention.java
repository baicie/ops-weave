package com.acme.opsweave.aicontrol.domain;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Content lifecycle, not permission to repeat a model call or remove cost/idempotency records. */
public final class AiRetention {
    private AiRetention() {}
    public static final int MAX_BATCH=100;
    public enum Kind { INSIGHT, EVIDENCE, AUDIT }
    public record Policy(TenantId tenantId,String version,int insightDays,int evidenceDays,int auditDays,int batchSize,Set<UUID> heldIncidents,boolean allowPurge) {
        public Policy {
            Objects.requireNonNull(tenantId);Objects.requireNonNull(version);heldIncidents=Set.copyOf(heldIncidents);
            if(!version.matches("[A-Za-z0-9._-]{1,64}") || insightDays<1 || insightDays>3650 || evidenceDays<1 || evidenceDays>3650 || auditDays<1 || auditDays>3650
                || batchSize<1 || batchSize>MAX_BATCH || heldIncidents.size()>1000) invalid();
        }
        public Instant cutoff(Kind kind,Instant asOf) { return asOf.truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofDays(switch(kind){case INSIGHT->insightDays;case EVIDENCE->evidenceDays;case AUDIT->auditDays;})); }
        public boolean eligible(Kind kind,UUID incident,Instant created,Instant expires,Instant asOf) {
            return !heldIncidents.contains(incident) && created.isBefore(cutoff(kind,asOf)) && expires.isBefore(asOf.truncatedTo(ChronoUnit.SECONDS));
        }
        public String digest() {return hash(String.join("\n","ai-retention-policy-v1",tenantId.value(),version,""+insightDays,""+evidenceDays,""+auditDays,""+batchSize,""+allowPurge,
            heldIncidents.stream().map(UUID::toString).sorted().reduce("",(a,b)->a+b+"\n")));}
    }
    public record Batch(Kind kind,List<UUID> ids,long logicalBytes,boolean hasMore) {
        public Batch {Objects.requireNonNull(kind);ids=List.copyOf(ids);if(ids.size()>MAX_BATCH || new HashSet<>(ids).size()!=ids.size() || logicalBytes<0 || logicalBytes>14_000_000)invalid();}
    }
    public record Preview(TenantId tenantId,SubjectId actor,String policyDigest,Instant asOf,List<Batch> batches) {
        public Preview {Objects.requireNonNull(tenantId);Objects.requireNonNull(actor);digestValue(policyDigest);instant(asOf);batches=List.copyOf(batches);
            if(asOf.getNano()!=0 || batches.size()!=3 || !batches.stream().map(Batch::kind).toList().equals(List.of(Kind.INSIGHT,Kind.EVIDENCE,Kind.AUDIT)))invalid();}
        public String digest(){var b=new StringBuilder("ai-retention-preview-v1\n").append(tenantId.value()).append('\n').append(actor.value()).append('\n').append(policyDigest).append('\n').append(asOf).append('\n');
            for(var batch:batches){b.append(batch.kind()).append(':').append(batch.logicalBytes()).append(':').append(batch.hasMore()).append('\n');for(var id:batch.ids())b.append(id).append('\n');}return hash(b.toString());}
        public void checkLive(Instant now){if(asOf.isAfter(now) || !now.isBefore(asOf.plusSeconds(120)))throw new ToolFailure(ToolFailure.Code.EXPIRED);}
    }
    public record Command(UUID requestId,String policyDigest,Instant asOf,String previewDigest) {
        public Command {Objects.requireNonNull(requestId);digestValue(policyDigest);digestValue(previewDigest);instant(asOf);if(asOf.getNano()!=0)invalid();}
    }
    public record Receipt(TenantId tenantId,SubjectId actor,Command command,Preview preview,Instant completedAt) {
        public Receipt {Objects.requireNonNull(tenantId);Objects.requireNonNull(actor);Objects.requireNonNull(command);Objects.requireNonNull(preview);instant(completedAt);
            if(!tenantId.equals(preview.tenantId()) || !actor.equals(preview.actor()) || !command.policyDigest().equals(preview.policyDigest()) || !command.previewDigest().equals(preview.digest())
                || !command.asOf().equals(preview.asOf()) || completedAt.isBefore(preview.asOf()))invalid();}
    }
    /** Authorization metadata survives content scrubbing. No question, summary, model output or provider payload. */
    public record Marker(Kind kind,TenantId tenantId,UUID id,UUID sessionId,SubjectId subjectId,UUID incidentId,long incidentVersion,
        Set<EntityId> entityIds,Set<String> metricKeys,Instant expiresAt,Instant purgedAt,String requestDigest) {
        public Marker {Objects.requireNonNull(kind);Objects.requireNonNull(tenantId);Objects.requireNonNull(id);Objects.requireNonNull(sessionId);Objects.requireNonNull(subjectId);Objects.requireNonNull(incidentId);
            entityIds=Set.copyOf(entityIds);metricKeys=Set.copyOf(metricKeys);instant(expiresAt);instant(purgedAt);
            if(kind==Kind.AUDIT || incidentVersion<1 || entityIds.size()>5 || metricKeys.size()>16 || metricKeys.stream().anyMatch(k->!k.matches("[A-Za-z0-9_][A-Za-z0-9_.:/%-]{0,127}")) || !expiresAt.isBefore(purgedAt))invalid();
            if(kind==Kind.INSIGHT)digestValue(requestDigest);else if(requestDigest!=null)invalid();}
    }
    public static void authorize(Principal p) {if(!p.has(Permission.AI_RETENTION_MANAGE) || !p.resourceScope().isTenantWide())throw new ToolFailure(ToolFailure.Code.FORBIDDEN);}
    private static void instant(Instant value){if(value==null || value.isBefore(Instant.EPOCH) || value.getEpochSecond()>253402300799L)invalid();}
    private static void digestValue(String value){if(value==null || !value.matches("sha256:[0-9a-f]{64}"))invalid();}
    private static String hash(String value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException();}}
    private static void invalid(){throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST);}
}
