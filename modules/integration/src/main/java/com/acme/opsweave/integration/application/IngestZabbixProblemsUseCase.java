package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.incident.api.IncidentStore;
import com.acme.opsweave.integration.domain.ProblemReadWindow;
import java.time.Clock;
import java.util.concurrent.Semaphore;
import com.acme.opsweave.integration.domain.ProblemReadException;

/** One explicit bounded page; a source failure cannot delete or resolve stored occurrences. */
public final class IngestZabbixProblemsUseCase {
    private final ReadZabbixProblemsUseCase reader;
    private final IncidentStore store;
    private final Clock clock;
    private final Semaphore permits = new Semaphore(2);
    public IngestZabbixProblemsUseCase(ReadZabbixProblemsUseCase reader, IncidentStore store, Clock clock) { this.reader = reader; this.store = store; this.clock = clock; }
    public Result execute(Principal principal, ProblemReadWindow window) {
        if (!permits.tryAcquire()) throw new ProblemReadException(ProblemReadException.Code.SOURCE_BUSY);
        try {
            var page = reader.execute(principal, window);
            IncidentStore.ImportResult saved;
            try { saved = store.ingest(principal.tenantId(), page.sourceInstanceId(), page.dataMode(), page.page().items(), clock.instant()); }
            catch (IllegalStateException unavailable) { throw new com.acme.opsweave.incident.domain.IncidentFailure(com.acme.opsweave.incident.domain.IncidentFailure.Code.UNAVAILABLE); }
            return new Result(page.dataMode(), page.sourceInstanceId(), saved, page.page().nextAfterEventId());
        } finally { permits.release(); }
    }
    public record Result(String dataMode, String sourceInstanceId, IncidentStore.ImportResult saved, String nextAfterEventId) {}
}
