package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The explicit binding between one source instance and one published workflow version for
 * <em>controlled batch collection</em>.
 *
 * <p>Publishing an immutable version never creates this binding and never starts collection. Only an
 * authorized, idempotent command with a mandatory operator reason does. While the binding is
 * {@link State#UNBOUND} no source batch of that version may be consumed; unbinding therefore stops
 * consumption immediately and a running task that loses its binding fails closed instead of quietly
 * continuing. This is deliberately a different object from {@link WorkflowRuntimeControl}: that one
 * is the task lifecycle (start/stop), this one is the source-instance/published-version relationship
 * to collection consumption.
 */
public final class WorkflowCollectionBinding {
    private WorkflowCollectionBinding() {}

    public enum Operation { ENABLE, DISABLE }

    /** What one binding currently says. The state is read back, never inferred from a publish. */
    public enum State { BOUND, UNBOUND }

    public record Command(UUID requestId, String id, int revision, String digest, String sourceInstanceId,
                          Operation operation, String reason) {
        public Command {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(operation, "operation");
            WorkflowDefinition.ref(id, revision);
            WorkflowDefinition.checkDigest(digest);
            checkSourceInstance(sourceInstanceId);
            if (reason == null || reason.isBlank() || reason.length() > 256 || reason.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid collection binding reason");
            }
        }

        /** Content-only digest so a replay of the same command is recognised and a change is not. */
        public String commandDigest() {
            return WorkflowDefinition.hash(List.of("workflow-collection-binding-v1", operation.name(), id,
                Integer.toString(revision), digest, sourceInstanceId, reason.trim()));
        }
    }

    public record Binding(String id, int revision, String digest, String sourceInstanceId, State state,
                          long generation, Instant updatedAt, String reason) {
        public Binding {
            WorkflowDefinition.ref(id, revision);
            WorkflowDefinition.checkDigest(digest);
            checkSourceInstance(sourceInstanceId);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (generation < 1) throw new IllegalArgumentException("Invalid collection binding generation");
            if (reason == null || reason.isBlank() || reason.length() > 256) throw new IllegalArgumentException("Invalid collection binding reason");
        }

        /** A successor keeps the object identity and only advances the generation. */
        public Binding successor(State next, long nextGeneration, Instant at, String nextReason) {
            if (nextGeneration != generation + 1) throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            return new Binding(id, revision, digest, sourceInstanceId, state == next ? state : next, nextGeneration, at, nextReason);
        }

        /** Exactly what consumption checks: same pinned version, same source instance, and bound. */
        public void requireConsumption(String workflowId, int revision, String digest, String sourceInstanceId) {
            if (state != State.BOUND || !id.equals(workflowId) || this.revision != revision || !this.digest.equals(digest)
                || !this.sourceInstanceId.equals(sourceInstanceId)) {
                throw new WorkflowFailure(WorkflowFailure.Code.COLLECTION_BINDING_REQUIRED);
            }
        }
    }

    public record Receipt(UUID requestId, Operation operation, String commandDigest, Instant createdAt, Binding binding) {
        public Receipt {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(operation, "operation");
            WorkflowDefinition.checkDigest(commandDigest);
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(binding, "binding");
            if (!binding.updatedAt().equals(createdAt)) throw new IllegalArgumentException("Collection receipt time mismatch");
            if (binding.state() != (operation == Operation.ENABLE ? State.BOUND : State.UNBOUND)) {
                throw new IllegalArgumentException("Collection receipt state mismatch");
            }
        }

        /** A replay must match the recorded command byte for byte; anything else is a conflict. */
        public void require(Command command) {
            if (!requestId.equals(command.requestId()) || operation != command.operation()
                || !commandDigest.equals(command.commandDigest()) || !binding.id().equals(command.id())
                || binding.revision() != command.revision() || !binding.digest().equals(command.digest())
                || !binding.sourceInstanceId().equals(command.sourceInstanceId())
                || !binding.reason().equals(command.reason().trim())) {
                throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
            }
        }
    }

    public static void checkSourceInstance(String sourceInstanceId) {
        if (sourceInstanceId == null || !sourceInstanceId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid collection source instance");
        }
    }

    /** Command operations are a closed set on the wire; unknown text is rejected, never guessed. */
    public static Operation operation(String value) {
        try {
            return Operation.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Invalid collection binding operation");
        }
    }
}
