package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Bounded historical reconstruction. Journals contain proofs, never sample values. */
public final class WorkflowLogReplay {
    private WorkflowLogReplay() {}
    public static final int MAX_POINTS = WorkflowLogWindow.MAX_RECORDS, MAX_PLANS = 200, VALID_SECONDS = 600, LOOKBACK_SECONDS = 86400;
    public static final Set<String> ERRORS = Set.of("FORBIDDEN", "SOURCE_UNAVAILABLE", "SOURCE_CHANGED", "MAPPING_CHANGED",
        "INVALID_SAMPLE", "WINDOW_INCOMPLETE", "SOURCE_WINDOW_CHANGED", "WINDOW_EXPIRED", "RUNTIME_UNAVAILABLE", "OUTPUT_REJECTED", "OUTPUT_UNCONFIRMED");

    public record Command(UUID requestId, String id, int revision, String digest, Instant from, Instant till) {
        public Command {
            Objects.requireNonNull(requestId); WorkflowDefinition.ref(id, revision); WorkflowDefinition.checkDigest(digest);
            Objects.requireNonNull(from); Objects.requireNonNull(till);
            if (from.getEpochSecond() < 0 || from.getNano() != 0 || !till.equals(from.plusSeconds(60))) throw new IllegalArgumentException();
        }
        public WorkflowQuality.Reference reference() { return new WorkflowQuality.Reference(id, revision, digest); }
        public String commandDigest() { return WorkflowDefinition.hash(List.of("log-replay-plan-v1", id, Integer.toString(revision), digest, from.toString(), till.toString())); }
        public void requireWindow(Instant now) {
            if (now.isBefore(till.plusSeconds(10)) || from.isBefore(now.minusSeconds(LOOKBACK_SECONDS))) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
        }
    }

    public record Proof(String inputDigest, String batchDigest, int inputCount, int filtered,
                        WorkflowLogOutput.Scope scope, List<Integer> indices, List<String> positions) {
        public Proof {
            WorkflowDefinition.checkDigest(inputDigest); WorkflowDefinition.checkDigest(batchDigest); Objects.requireNonNull(scope);
            indices = List.copyOf(indices); positions = List.copyOf(positions);
            if (inputCount < 0 || inputCount > MAX_POINTS || filtered < 0 || filtered + indices.size() != inputCount || positions.size() != inputCount) throw new IllegalArgumentException();
            int previous = -1; for (var index : indices) { if (index <= previous || index >= inputCount) throw new IllegalArgumentException(); previous = index; }
            Instant last = null; for (var text : positions) { var stamp = Instant.parse(text); if (!stamp.toString().equals(text) || stamp.getEpochSecond() < 0 || last != null && !stamp.isAfter(last)) throw new IllegalArgumentException(); last = stamp; }
        }
        public boolean matches(WorkflowLogWindow.Batch batch) {
            return scope.equals(batch.scope()) && inputCount == batch.inputCount() && filtered == batch.filtered() && batch.deduplicated() == 0
                && indices.equals(batch.records().stream().map(WorkflowLogWindow.Record::index).toList())
                && indices.stream().map(positions::get).toList().equals(batch.records().stream().map(WorkflowLogWindow.Record::position).toList())
                && batch.digest().equals(batchDigest);
        }
    }

    public record Plan(String schemaVersion, UUID requestId, WorkflowQuality.Reference reference, String commandDigest,
                       Instant from, Instant till, Instant createdAt, Instant updatedAt, Instant expiresAt,
                       String purpose, String outputPolicy, boolean notifications, boolean actions, String state, Proof proof, String error) {
        public Plan {
            Objects.requireNonNull(requestId); Objects.requireNonNull(reference); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt); Objects.requireNonNull(expiresAt);
            var command = new Command(requestId, reference.id(), reference.revision(), reference.digest(), from, till);
            command.requireWindow(createdAt);
            if (!"2.0".equals(schemaVersion) || !command.commandDigest().equals(commandDigest) || !expiresAt.equals(createdAt.plusSeconds(VALID_SECONDS))
                || updatedAt.isBefore(createdAt) || !"REBUILD_LOG_PROJECTION".equals(purpose) || !"ISOLATED_LOG_WINDOW".equals(outputPolicy)
                || notifications || actions || !Set.of("PREPARING", "READY", "FAILED").contains(state) || state.equals("READY") != (proof != null)
                || state.equals("FAILED") != (error != null) || error != null && (!ERRORS.contains(error) || error.startsWith("OUTPUT_"))) throw new IllegalArgumentException();
            if (proof != null) {
                var scope = proof.scope();
                if (!requestId.equals(scope.requestId()) || !reference.id().equals(scope.workflowId()) || reference.revision() != scope.revision() || !reference.digest().equals(scope.digest())
                    || proof.positions().stream().map(Instant::parse).anyMatch(t -> t.isBefore(from) || !t.isBefore(till))) throw new IllegalArgumentException();
                if (proof.indices().isEmpty() && !new WorkflowLogWindow.Batch(scope, from, till, proof.inputCount(), proof.filtered(), List.of()).digest().equals(proof.batchDigest())) throw new IllegalArgumentException();
            }
        }
        public static Plan preparing(Command c, Instant time) {
            return new Plan("2.0", c.requestId(), c.reference(), c.commandDigest(), c.from(), c.till(), time, time, time.plusSeconds(VALID_SECONDS), "REBUILD_LOG_PROJECTION", "ISOLATED_LOG_WINDOW", false, false, "PREPARING", null, null);
        }
        public Plan finish(Proof proof, String error, Instant time) {
            return new Plan(schemaVersion, requestId, reference, commandDigest, from, till, createdAt, time, expiresAt, purpose, outputPolicy, notifications, actions, proof == null ? "FAILED" : "READY", proof, error);
        }
        public void require(Command c) { if (!requestId.equals(c.requestId()) || !reference.equals(c.reference()) || !commandDigest.equals(c.commandDigest())) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT); }
        public void requireSuccessor(Plan next) {
            if (!state.equals("PREPARING") || next.state().equals("PREPARING") || !finish(next.proof(), next.error(), next.updatedAt()).equals(next) || next.updatedAt().isBefore(updatedAt)) throw new IllegalStateException("Invalid replay preparation refinement");
        }
        public void requireExecutable(Instant time) {
            if (!state.equals("READY") || time.isBefore(createdAt) || !time.isBefore(expiresAt) || from.isBefore(time.minusSeconds(LOOKBACK_SECONDS))) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
        }
    }

    public record Execute(UUID requestId, UUID planId, String inputDigest, String batchDigest) {
        public Execute { Objects.requireNonNull(requestId); Objects.requireNonNull(planId); WorkflowDefinition.checkDigest(inputDigest); WorkflowDefinition.checkDigest(batchDigest); }
        public String commandDigest() { return WorkflowDefinition.hash(List.of("log-replay-execute-v1", planId.toString(), inputDigest, batchDigest)); }
        public void require(Plan p) { if (!planId.equals(p.requestId()) || p.proof() == null || !inputDigest.equals(p.proof().inputDigest()) || !batchDigest.equals(p.proof().batchDigest())) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT); }
    }
    public record Receipt(String schemaVersion, UUID requestId, UUID planId, WorkflowQuality.Reference reference,
                          String commandDigest, Instant acceptedAt, Instant updatedAt, String state, String error) {
        public Receipt {
            Objects.requireNonNull(requestId); Objects.requireNonNull(planId); Objects.requireNonNull(reference); Objects.requireNonNull(acceptedAt); Objects.requireNonNull(updatedAt); WorkflowDefinition.checkDigest(commandDigest);
            if (!"2.0".equals(schemaVersion) || updatedAt.isBefore(acceptedAt) || !Set.of("PENDING", "CONFIRMED", "UNKNOWN", "FAILED").contains(state)
                || Set.of("UNKNOWN", "FAILED").contains(state) != (error != null) || error != null && !ERRORS.contains(error)
                || state.equals("UNKNOWN") && !"OUTPUT_UNCONFIRMED".equals(error) || state.equals("FAILED") && "OUTPUT_UNCONFIRMED".equals(error)) throw new IllegalArgumentException();
        }
        public void require(Execute c) { if (!requestId.equals(c.requestId()) || !planId.equals(c.planId()) || !commandDigest.equals(c.commandDigest())) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT); }
        public Receipt finish(String state, String error, Instant time) { return new Receipt(schemaVersion, requestId, planId, reference, commandDigest, acceptedAt, time, state, error); }
        public void requireSuccessor(Receipt next) {
            if (Set.of("CONFIRMED", "FAILED").contains(state) || state.equals("UNKNOWN") && !Set.of("UNKNOWN", "CONFIRMED").contains(next.state())
                || next.state().equals("PENDING") || !finish(next.state(), next.error(), next.updatedAt()).equals(next) || next.updatedAt().isBefore(updatedAt)) throw new IllegalStateException("Invalid replay output refinement");
        }
    }
}
