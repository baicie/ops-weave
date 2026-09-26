package com.acme.opsweave.integration.application;

import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.api.SourceConnectionCheckStore;
import com.acme.opsweave.integration.domain.SourceConnectionCheck;
import com.acme.opsweave.integration.domain.SourceConnectionCheckException;
import com.acme.opsweave.integration.domain.SourceConnectionCheckException.Code;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only source self-check. It calls the connector's bounded probe, records what the source
 * reported and never contacts inventory, a scan lease or a model. A failing probe stays a failed
 * receipt: nothing here falls back to a fixture or turns an unreachable source into a success.
 */
public final class SourceConnectionCheckService {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = SourceConnectionCheckStore.MAX_RECENT;

    private final AuthorizationService authorization;
    private final Connector connector;
    private final SourceConnectionCheckStore checks;
    private final String source;
    private final String dataMode;
    private final String secretRef;
    private final Clock clock;

    public SourceConnectionCheckService(
        AuthorizationService authorization,
        Connector connector,
        SourceConnectionCheckStore checks,
        String source,
        String dataMode,
        String secretRef,
        Clock clock
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.checks = Objects.requireNonNull(checks, "checks");
        this.source = source;
        this.dataMode = Objects.requireNonNull(dataMode, "dataMode");
        this.secretRef = secretRef;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SourceConnectionCheck check(Principal principal) {
        authorize(principal);
        Connector.ProbeResult result;
        try {
            result = connector.probe(new Connector.SourceContext(principal.tenantId(), source, secretRef));
        } catch (RuntimeException failed) {
            // A connector that throws must not become a successful check.
            result = new Connector.ProbeResult(false, "unreachable");
        }
        SourceConnectionCheck receipt = new SourceConnectionCheck(
            UUID.randomUUID(),
            principal.tenantId(),
            source,
            principal.subjectId().value(),
            clock.instant(),
            dataMode,
            result.reachable(),
            result.statusCode(),
            result.reportedVersion()
        );
        checks.record(receipt);
        return receipt;
    }

    public List<SourceConnectionCheck> recent(Principal principal, int limit) {
        authorize(principal);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new SourceConnectionCheckException(Code.INVALID_REQUEST);
        }
        return checks.recent(principal.tenantId(), source, limit);
    }

    private void authorize(Principal principal) {
        if (principal == null) {
            throw new SourceConnectionCheckException(Code.FORBIDDEN);
        }
        if (source == null || source.isBlank()) {
            throw new SourceConnectionCheckException(Code.UNCONFIGURED);
        }
        if (authorization.authorize(principal, ResourceRef.source(principal.tenantId(), source), Permission.SOURCE_SYNC).denied()) {
            throw new SourceConnectionCheckException(Code.FORBIDDEN);
        }
    }
}
