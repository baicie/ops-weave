package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.api.ReceiptRetentionStore;
import com.acme.opsweave.integration.domain.ReceiptRetention;
import com.acme.opsweave.integration.domain.WorkflowFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The receipt retention budget and its explicit preview → confirm cleanup.
 *
 * <p>Nothing here runs on a schedule and nothing runs as a side effect of a read. The budget only
 * reports how much of a scope is cleanable, the preview only lists candidates and the confirm is a
 * reasoned, idempotent command whose receipt keeps the counts. Protected rows are never deleted and
 * unfinished records never enter the candidate set at all.
 */
public final class ReceiptRetentionService {
    public static final int MAX_REMOVE = 100;
    /** Reads are bounded: a preview never walks a whole table. */
    public static final int MAX_SCAN_ROWS = ReceiptRetention.MAX_SCAN_ROWS;

    public record Result(ReceiptRetention.Receipt receipt, boolean replayed) {}

    private final ReceiptRetentionStore store;
    private final ReceiptRetention.Policy policy;
    private final Clock clock;

    public ReceiptRetentionService(ReceiptRetentionStore store, ReceiptRetention.Policy policy, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Read-only budget for one scope; reading never prunes and never authorizes a later delete. */
    public ReceiptRetention.Report report(Principal p, ReceiptRetention.Kind kind, String scopeKey) {
        authorize(p);
        Objects.requireNonNull(kind, "kind");
        ReceiptRetention.checkScopeKey(scopeKey);
        return report(kind, scopeKey, store.rows(p.tenantId(), kind, scopeKey, MAX_SCAN_ROWS));
    }

    /** Every visible scope of every kind, bounded: the ceiling is only useful if it is visible. */
    public List<ReceiptRetention.Report> reports(Principal p) {
        authorize(p);
        var reports = new ArrayList<ReceiptRetention.Report>();
        for (var kind : ReceiptRetention.Kind.values()) {
            for (var scopeKey : store.scopes(p.tenantId(), kind)) {
                if (reports.size() >= 50) return List.copyOf(reports);
                reports.add(report(kind, scopeKey, store.rows(p.tenantId(), kind, scopeKey, MAX_SCAN_ROWS)));
            }
        }
        return List.copyOf(reports);
    }

    private ReceiptRetention.Report report(ReceiptRetention.Kind kind, String scopeKey, List<ReceiptRetention.Row> rows) {
        int kept = 0, protectedRows = 0;
        for (var row : rows) { if (row.protectedRow()) protectedRows++; else kept++; }
        return new ReceiptRetention.Report(kind, scopeKey, kept, protectedRows, policy.maxPerScope(), policy.maxPerTenant());
    }

    /** Read-only preview: exactly which rows a confirm would remove, and how many stay protected. */
    public ReceiptRetention.Preview preview(Principal p, ReceiptRetention.Kind kind, String scopeKey, int maxRemove) {
        authorize(p);
        Objects.requireNonNull(kind, "kind");
        ReceiptRetention.checkScopeKey(scopeKey);
        return preview(kind, scopeKey, store.rows(p.tenantId(), kind, scopeKey, MAX_SCAN_ROWS), maxRemove);
    }

    static ReceiptRetention.Preview preview(ReceiptRetention.Kind kind, String scopeKey,
                                           List<ReceiptRetention.Row> rows, int maxRemove) {
        if (maxRemove < 1 || maxRemove > MAX_REMOVE) throw new IllegalArgumentException("Invalid receipt cleanup limit");
        var candidates = new ArrayList<String>();
        int protectedRows = 0;
        for (var row : rows) {
            if (row.protectedRow()) { protectedRows++; continue; }
            if (candidates.size() < maxRemove) candidates.add(row.id());
        }
        return new ReceiptRetention.Preview(kind, scopeKey, ReceiptRetention.Preview.digest(kind, scopeKey, candidates),
            candidates, candidates.size(), protectedRows, 0, maxRemove);
    }

    /** A replay returns the recorded counts; a changed world is a conflict, never a wider deletion. */
    public Result confirm(Principal p, ReceiptRetention.Command command) {
        authorize(p);
        Objects.requireNonNull(command, "command");
        var prior = store.command(p.tenantId(), command.requestId());
        if (prior.isPresent()) {
            prior.get().require(command);
            return new Result(prior.get(), true);
        }
        var planned = preview(command.kind(), command.scopeKey(),
            store.rows(p.tenantId(), command.kind(), command.scopeKey(), MAX_SCAN_ROWS), command.maxRemove());
        if (!planned.previewDigest().equals(command.previewDigest())) throw fail(WorkflowFailure.Code.CONFLICT);
        var removed = store.remove(p.tenantId(), command.kind(), command.scopeKey(), planned.candidates());
        if (removed != planned.removable()) throw fail(WorkflowFailure.Code.CONFLICT);
        var receipt = new ReceiptRetention.Receipt(command.requestId(), command.commandDigest(), now(),
            command.kind(), command.scopeKey(), removed, planned.keptProtected(), 0, command.reason().trim());
        receipt.require(command);
        store.addCommand(p.tenantId(), receipt);
        return new Result(receipt, false);
    }

    public ReceiptRetention.Receipt receipt(Principal p, UUID requestId) {
        authorize(p);
        Objects.requireNonNull(requestId, "requestId");
        return store.command(p.tenantId(), requestId).orElseThrow(() -> fail(WorkflowFailure.Code.NOT_FOUND));
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /** Retention governance is an operator action over both the receipt and its source scope. */
    private void authorize(Principal p) {
        if (!p.has(Permission.ENTITY_MANAGE) || !p.has(Permission.SOURCE_SYNC)) {
            throw fail(WorkflowFailure.Code.FORBIDDEN);
        }
    }

    private static WorkflowFailure fail(WorkflowFailure.Code code) {
        return new WorkflowFailure(code);
    }
}
