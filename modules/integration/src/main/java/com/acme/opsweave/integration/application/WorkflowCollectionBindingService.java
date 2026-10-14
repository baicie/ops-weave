package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.Authorizer;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.WorkflowCollectionBinding;
import com.acme.opsweave.integration.domain.WorkflowFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The explicit enable/disable of collection binding for one published workflow version.
 *
 * <p>This never starts collection, never writes an entity and never calls a model. It records (or
 * revokes) the relationship between one source instance and one published version and hands back an
 * immutable, replayable receipt. Whether a batch may actually be consumed is decided separately by
 * {@link WorkflowRuntimeService} against the recorded binding, so a page that merely reads state
 * cannot unlock consumption.
 */
public final class WorkflowCollectionBindingService {
    /** Bounded command receipts per owner, mirroring the runtime control ceiling. */
    public static final int MAX_COMMANDS_PER_OWNER = 200;

    public record Result(WorkflowCollectionBinding.Receipt receipt, boolean replayed) {}

    private final WorkflowStore store;
    private final Clock clock;

    public WorkflowCollectionBindingService(WorkflowStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A replay returns the recorded acknowledgement and never re-admits or changes the binding. */
    public Result command(Principal p, WorkflowCollectionBinding.Command command) {
        Objects.requireNonNull(command, "command");
        authorizeRead(p);
        return store.transaction(p.tenantId(), s -> {
            var owner = p.subjectId().value();
            var prior = s.collectionBindingCommand(owner, command.requestId());
            if (prior.isPresent()) {
                prior.get().require(command);
                authorize(p, command.id(), command.sourceInstanceId());
                return new Result(prior.get(), true);
            }
            authorize(p, command.id(), command.sourceInstanceId());
            if (s.collectionBindingCommandCount(owner) >= MAX_COMMANDS_PER_OWNER) throw fail(WorkflowFailure.Code.CAPACITY);
            // The bound object must be a real published version with the exact pinned digest.
            var entry = s.published(command.id(), command.revision()).orElseThrow(() -> fail(WorkflowFailure.Code.NOT_FOUND));
            if (!entry.digest().equals(command.digest())) throw fail(WorkflowFailure.Code.CONFLICT);
            var previous = s.collectionBinding(owner, command.id(), command.revision());
            var at = now();
            var state = command.operation() == WorkflowCollectionBinding.Operation.ENABLE
                ? WorkflowCollectionBinding.State.BOUND : WorkflowCollectionBinding.State.UNBOUND;
            var binding = previous.isEmpty()
                ? new WorkflowCollectionBinding.Binding(command.id(), command.revision(), command.digest(),
                    command.sourceInstanceId(), state, 1, at, command.reason().trim())
                : previous.get().successor(state, previous.get().generation() + 1, at, command.reason().trim());
            var receipt = new WorkflowCollectionBinding.Receipt(command.requestId(), command.operation(),
                command.commandDigest(), at, binding);
            receipt.require(command);
            s.saveCollectionBinding(owner, binding);
            s.addCollectionBindingCommand(owner, receipt);
            return new Result(receipt, false);
        });
    }

    public WorkflowCollectionBinding.Receipt receipt(Principal p, UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        authorizeRead(p);
        return store.transaction(p.tenantId(), s -> {
            var receipt = s.collectionBindingCommand(p.subjectId().value(), requestId)
                .orElseThrow(() -> fail(WorkflowFailure.Code.NOT_FOUND));
            authorize(p, receipt.binding().id(), receipt.binding().sourceInstanceId());
            return receipt;
        });
    }

    /** Readable binding state only; reading never enables, disables or starts anything. */
    public List<WorkflowCollectionBinding.Binding> bindings(Principal p) {
        authorizeRead(p);
        return store.transaction(p.tenantId(), s -> s.collectionBindings(p.subjectId().value()).stream()
            .filter(b -> new Authorizer().decide(p, new ResourceRef(p.tenantId(), "workflow", b.id()), Permission.SOURCE_SYNC).allowed())
            .toList());
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private void authorizeRead(Principal p) {
        if (new Authorizer().decide(p, new ResourceRef(p.tenantId(), "workflow", "*"), Permission.SOURCE_SYNC).denied()) {
            throw fail(WorkflowFailure.Code.FORBIDDEN);
        }
    }

    /** Enabling collection is a write over the workflow and its source instance, never a read. */
    private void authorize(Principal p, String id, String sourceInstanceId) {
        var auth = new Authorizer();
        if (auth.decide(p, new ResourceRef(p.tenantId(), "workflow", id), Permission.SOURCE_SYNC).denied()
            || auth.decide(p, ResourceRef.source(p.tenantId(), sourceInstanceId), Permission.SOURCE_SYNC).denied()
            || !p.has(Permission.ENTITY_MANAGE)) {
            throw fail(WorkflowFailure.Code.FORBIDDEN);
        }
    }

    private static WorkflowFailure fail(WorkflowFailure.Code code) {
        return new WorkflowFailure(code);
    }
}
