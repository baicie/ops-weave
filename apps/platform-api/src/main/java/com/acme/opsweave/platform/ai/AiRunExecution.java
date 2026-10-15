package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.application.AiRunControlService;
import com.acme.opsweave.aicontrol.application.AiRunExecutionService;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.api.BearerCredentials;
import com.acme.opsweave.identity.api.PrincipalResolver;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The AIRun executor wiring (M5 5b): a submission nudges the executor so the run is claimed
 * {@code PENDING -> RUNNING} right away, and a bounded periodic sweep recovers runs whose worker
 * died (expired lease) or whose nudge was lost. Execution is bounded (four in-flight attempts, no
 * queue), every attempt re-checks identity, lease and budget, and a dispatch failure lands the run
 * {@code FAILED} with its reason — there is no silent fallback when the Runtime port is missing.
 *
 * <p>The 5b demo identity is the loopback dev mode (fixed dev token): the executor resolves the
 * current principal from it and executes only that owner's runs. Delegated (OIDC) background
 * execution is deliberately not wired here and stays an explicit boundary.
 */
@Component
public final class AiRunExecution implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(AiRunExecution.class.getName());

    private final AiRunExecutionService execution;
    private final AiRunControlService control;
    private final com.acme.opsweave.aicontrol.application.AiRunRuntimeStateService runtimeStates;
    private final PrincipalResolver resolver;
    private final OpsweaveProperties properties;
    private final ExecutorService nudges = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits = new Semaphore(4);

    public AiRunExecution(RuntimeDispatcher runtime, PrincipalResolver resolver, OpsweaveProperties properties,
            AuthorizationService authorization, IncidentService incidents, InventoryWiring wiring) {
        this.resolver = resolver;
        this.properties = properties;
        var dev = properties.auth().dev();
        var dispatch = new RuntimeAiRunDispatch(runtime, dev == null ? "" : dev.token());
        this.execution = new AiRunExecutionService(wiring.aiRuns(), wiring.insights(), wiring.modelSpend(), dispatch,
            authorization, incidents, Clock.systemUTC(), "platform-api:" + UUID.randomUUID());
        this.control = new AiRunControlService(wiring.aiRuns(), wiring.insights(), wiring.modelSpend(), this.execution,
            authorization, incidents, Clock.systemUTC());
        this.runtimeStates = new com.acme.opsweave.aicontrol.application.AiRunRuntimeStateService(
            wiring.aiRuns(), wiring.aiRunRuntimeStates(), wiring.insights(), wiring.modelSpend(), Clock.systemUTC());
    }

    public AiRunExecutionService executor() {
        return execution;
    }

    public AiRunControlService control() {
        return control;
    }

    /** The 5d cross-store seams: Runtime state delivery, disclosure-only reconciliation, aggregates. */
    public com.acme.opsweave.aicontrol.application.AiRunRuntimeStateService runtimeStates() {
        return runtimeStates;
    }

    /**
     * Submission-time nudge: the executor claims and runs the new submission immediately. A busy
     * executor never queues it — the periodic sweep picks the run up later instead.
     */
    public void schedule(UUID runId, Principal submitter) {
        if (!enabled() || !permits.tryAcquire()) return;
        try {
            nudges.execute(() -> {
                try {
                    execution.execute(submitter.tenantId(), runId, submitter);
                } catch (RuntimeException failure) {
                    LOG.log(Level.WARNING, "AI run attempt did not finish; the lease sweep recovers it");
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException unavailable) {
            permits.release();
        }
    }

    /** The recovery sweep: claim and execute this owner's pending or lease-expired runs. */
    @Scheduled(fixedDelay = 5000)
    public void poll() {
        if (!enabled()) return;
        try {
            var current = resolver.resolve(new BearerCredentials(properties.auth().dev().token())).orElse(null);
            if (current != null) {
                execution.tick(current, 4);
                // 5d cross-store reconciliation: bounded, disclose-only. It compares the registry
                // with the Runtime's delivered state and records the report snapshot — it never
                // repairs, never re-runs and never hands a run to a second worker.
                runtimeStates.reconcileRecent(current, 4);
            }
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "AI run sweep did not finish; the next sweep retries with fresh identity");
        }
    }

    private boolean enabled() {
        return "dev".equals(properties.auth().mode()) && properties.auth().bindLoopbackOnly()
            && properties.auth().dev() != null;
    }

    @PreDestroy
    @Override
    public void close() {
        nudges.shutdownNow();
    }
}
