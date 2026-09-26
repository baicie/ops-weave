package com.acme.opsweave.aicontrol.domain;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;

/** Local admission/accounting estimates, not a provider invoice or a grant from model output. */
public final class ModelSpend {
    private ModelSpend() {}
    public static final int MAX_INPUT_BYTES = 73_728, MAX_INPUT_TOKENS = 81_920, MAX_OUTPUT_TOKENS = 2_048;
    public static final int MAX_RECORDS = 10_000;
    public static final long MAX_MONEY = 1_000_000_000_000L;
    public record Policy(String provider, String model, String priceVersion, long inputMicrosPerMillion,
            long outputMicrosPerMillion, long maxCallMicros, long dailyMicros) {
        public Policy {
            new InsightSubmission.ModelRef(provider, model);
            if(priceVersion == null || !priceVersion.matches("[a-zA-Z0-9._-]{1,64}")) invalid();
            for(long value : new long[]{inputMicrosPerMillion,outputMicrosPerMillion,maxCallMicros,dailyMicros}) if(value < 0 || value > MAX_MONEY) invalid();
            if(provider.equals("mock-deterministic")) {
                if(!model.equals("mock-current-v1") || !priceVersion.equals("mock-no-charge") || inputMicrosPerMillion != 0 || outputMicrosPerMillion != 0 || maxCallMicros != 0 || dailyMicros != 0) invalid();
            } else if(inputMicrosPerMillion == 0 || outputMicrosPerMillion == 0 || maxCallMicros == 0 || dailyMicros < maxCallMicros) invalid();
        }
        public static Policy mock() { return new Policy("mock-deterministic","mock-current-v1","mock-no-charge",0,0,0,0); }
        public long estimate(int inputTokens, int outputTokens) {
            if(inputTokens < 0 || inputTokens > MAX_INPUT_TOKENS || outputTokens < 0 || outputTokens > MAX_OUTPUT_TOKENS) invalid();
            // Bounded integer arithmetic. Round up once; cached input is conservatively charged at the full input rate.
            return Math.floorDiv(Math.addExact(Math.addExact(Math.multiplyExact((long)inputTokens,inputMicrosPerMillion),
                Math.multiplyExact((long)outputTokens,outputMicrosPerMillion)),999_999),1_000_000);
        }
        public long reservationMicros() { return estimate(MAX_INPUT_TOKENS,MAX_OUTPUT_TOKENS); }
    }
    public record Usage(int inputTokens, int outputTokens, int cachedInputTokens, String source) {
        public Usage {
            if(inputTokens < 0 || inputTokens > MAX_INPUT_TOKENS || outputTokens < 0 || outputTokens > MAX_OUTPUT_TOKENS
                || cachedInputTokens < 0 || cachedInputTokens > inputTokens || !Set.of("provider-reported","mock-no-call").contains(source)) invalid();
            if(source.equals("mock-no-call") ? inputTokens != 0 || outputTokens != 0 || cachedInputTokens != 0 : inputTokens == 0) invalid();
        }
    }
    public record Call(TenantId tenantId, SubjectId subjectId, UUID runId, UUID sessionId, UUID incidentId,
            String inputDigest, int inputBytes, Policy policy, Instant reservedAt, Instant deadlineAt, Usage usage, Instant reportedAt) {
        public Call {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(subjectId); Objects.requireNonNull(runId); Objects.requireNonNull(sessionId); Objects.requireNonNull(incidentId);
            Objects.requireNonNull(policy); Objects.requireNonNull(reservedAt); Objects.requireNonNull(deadlineAt);
            if(inputDigest == null || !inputDigest.matches("sha256:[0-9a-f]{64}") || inputBytes < 1 || inputBytes > MAX_INPUT_BYTES
                || reservedAt.isBefore(Instant.EPOCH) || !deadlineAt.isAfter(reservedAt) || deadlineAt.isAfter(reservedAt.plusSeconds(60))
                || ((usage == null) != (reportedAt == null))) invalid();
            if(usage != null && (reportedAt.isBefore(reservedAt) || !policy.provider().equals(usage.source().equals("mock-no-call") ? "mock-deterministic" : "rig-openai"))) invalid();
        }
        public LocalDate day() { return reservedAt.atOffset(ZoneOffset.UTC).toLocalDate(); }
        public long reservedMicros() { return policy.reservationMicros(); }
        public long chargedMicros() { return usage == null ? reservedMicros() : policy.estimate(usage.inputTokens(),usage.outputTokens()); }
        public boolean countsOn(LocalDate day) { return usage == null || day().equals(day); }
        public String state(Instant now) { return usage != null ? "REPORTED" : now.isBefore(deadlineAt) ? "RESERVED" : "UNCERTAIN"; }
        public Call report(Usage value, Instant now) {
            Objects.requireNonNull(value);
            if(usage != null) { if(!usage.equals(value)) throw new ToolFailure(ToolFailure.Code.INPUT_CHANGED); return this; }
            return new Call(tenantId,subjectId,runId,sessionId,incidentId,inputDigest,inputBytes,policy,reservedAt,deadlineAt,value,now);
        }
    }
    public static void admit(Call call, long accountedMicros, long retainedRecords) {
        if(call.usage()!=null || accountedMicros < 0 || retainedRecords < 0) invalid();
        if(retainedRecords >= MAX_RECORDS || call.reservedMicros() > call.policy().maxCallMicros()
            || accountedMicros > call.policy().dailyMicros() || call.reservedMicros() > call.policy().dailyMicros()-accountedMicros)
            throw new ToolFailure(ToolFailure.Code.BUDGET_EXHAUSTED);
    }
    private static void invalid() { throw new ToolFailure(ToolFailure.Code.INVALID_REQUEST); }
}
