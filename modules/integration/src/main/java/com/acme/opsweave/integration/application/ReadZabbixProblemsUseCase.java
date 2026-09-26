package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.domain.*;
import java.math.BigInteger;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.Semaphore;
import static com.acme.opsweave.integration.domain.ProblemReadException.Code.*;

/** Source-administrator read. No inventory reconciliation, incident write, notification or action. */
public final class ReadZabbixProblemsUseCase {
    private final AuthorizationService authorization;
    private final ZabbixProblemPort port;
    private final String source, secretRef, dataMode;
    private final Clock clock;
    private final Semaphore permits = new Semaphore(2);
    public ReadZabbixProblemsUseCase(AuthorizationService authorization, ZabbixProblemPort port,
            String source, String secretRef, String dataMode, Clock clock) {
        if (!Set.of("closed", "labeled-fixture", "zabbix-jsonrpc").contains(dataMode)) throw new IllegalArgumentException("Invalid problem mode");
        this.authorization = authorization; this.port = port; this.source = source; this.secretRef = secretRef; this.dataMode = dataMode; this.clock = clock;
    }
    public Result execute(Principal principal, ProblemReadWindow window) {
        if (authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.SOURCE_SYNC).denied()) throw new ProblemReadException(FORBIDDEN);
        if (window.till() > clock.instant().getEpochSecond()) throw new IllegalArgumentException("Future problem window");
        if (dataMode.equals("closed")) throw new ProblemReadException(SOURCE_UNAVAILABLE);
        if (!permits.tryAcquire()) throw new ProblemReadException(SOURCE_BUSY);
        try {
            var page = port.read(new Connector.SourceContext(principal.tenantId(), source, secretRef), window);
            BigInteger previous = new BigInteger(window.afterEventId() == null ? "0" : window.afterEventId());
            if (page.items().size() > window.limit()) throw new ProblemReadException(INVALID_SOURCE_RESPONSE);
            for (var item : page.items()) {
                if (!item.tenantId().equals(principal.tenantId()) || !item.sourceInstanceId().equals(source)
                    || new BigInteger(item.problemEventId()).compareTo(previous) <= 0 || item.observedAt().isAfter(clock.instant())
                    || item.occurredAt().getEpochSecond() > window.till()
                    || (item.recoveredAt() != null && item.recoveredAt().getEpochSecond() < window.from())) throw new ProblemReadException(INVALID_SOURCE_RESPONSE);
                previous = new BigInteger(item.problemEventId());
            }
            if (page.nextAfterEventId() != null && (page.items().size() != window.limit()
                || !page.nextAfterEventId().equals(page.items().getLast().problemEventId()))) throw new ProblemReadException(INVALID_SOURCE_RESPONSE);
            return new Result(dataMode, source, page);
        } finally { permits.release(); }
    }
    public record Result(String dataMode, String sourceInstanceId, ZabbixProblemPort.Page page) {}
}
