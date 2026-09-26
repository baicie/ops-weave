import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.SourceConnectionCheckService;
import com.acme.opsweave.integration.domain.SourceConnectionCheck;
import com.acme.opsweave.integration.domain.SourceConnectionCheckException;
import com.acme.opsweave.integration.infrastructure.FixtureZabbixHostConnector;
import com.acme.opsweave.integration.infrastructure.InMemorySourceConnectionCheckStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/** A source self-check reports what the source said, and never turns a failure into a success. */
public final class SourceConnectionCheckSmoke {
    private static final TenantId TENANT = new TenantId("tenant-connection-check");
    private static final TenantId OTHER = new TenantId("tenant-connection-check-other");
    private static final String SOURCE = "zabbix-1";
    private static final Principal READER = new Principal(
        new SubjectId("operator"), TENANT, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide()
    );
    private static int checks = 0;

    public static void main(String[] args) {
        var clock = new MutableClock(Instant.parse("2026-09-26T06:00:00Z"));
        var store = new InMemorySourceConnectionCheckStore();

        fails(SourceConnectionCheckException.Code.FORBIDDEN, () -> service(new FixtureZabbixHostConnector(), store, clock).check(null));
        fails(SourceConnectionCheckException.Code.FORBIDDEN, () -> service(new FixtureZabbixHostConnector(), store, clock)
            .check(new Principal(new SubjectId("operator"), TENANT, Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide())));
        fails(SourceConnectionCheckException.Code.UNCONFIGURED, () -> new SourceConnectionCheckService(
            new AuthorizeUseCase(), new FixtureZabbixHostConnector(), store, "  ", "labeled-fixture", "ref", clock).check(READER));

        // The fixture probe is explicitly labeled and claims no vendor version.
        var fixture = service(new FixtureZabbixHostConnector(), store, clock).check(READER);
        require(fixture.reachable(), "the fixture probe is reachable");
        require("labeled-fixture".equals(fixture.statusCode()), "the fixture probe stays labeled");
        require(fixture.reportedVersion() == null, "the fixture probe claims no vendor version");
        require("labeled-fixture".equals(fixture.dataMode()), "the receipt records the configured mode");
        require("operator".equals(fixture.actor()) && TENANT.equals(fixture.tenantId()), "the receipt keeps the trusted actor and tenant");
        require(Instant.parse("2026-09-26T06:00:00Z").equals(fixture.checkedAt()), "the receipt uses the service clock");

        // A source that reports a version keeps it verbatim: it is a claim, not a verified support statement.
        var reporting = service(new ReportingConnector(true, "7.0.4"), store, clock).check(READER);
        require(reporting.reachable() && "7.0.4".equals(reporting.reportedVersion()), "a reported version is stored as reported");

        // An unreachable source is a failed receipt with no version, and a throwing connector fails closed.
        var unreachable = service(new ReportingConnector(false, null), store, clock).check(READER);
        require(!unreachable.reachable() && "unreachable".equals(unreachable.statusCode()), "an unreachable source stays unreachable");
        require(unreachable.reportedVersion() == null, "an unreachable receipt carries no version");
        var thrown = service(new ThrowingConnector(), store, clock).check(READER);
        require(!thrown.reachable() && "unreachable".equals(thrown.statusCode()), "a connector that throws is not a success");
        require(thrown.reportedVersion() == null, "a failed probe carries no version");

        // Receipts are newest-first, bounded per scope and isolated per tenant/source.
        var bounded = new InMemorySourceConnectionCheckStore();
        var boundedService = service(new FixtureZabbixHostConnector(), bounded, clock);
        for (int i = 0; i < SourceConnectionCheck.MAX_KEPT + 5; i++) {
            clock.advance(Duration.ofSeconds(1));
            boundedService.check(READER);
        }
        var recent = boundedService.recent(READER, 50);
        require(recent.size() == 50, "a read page is bounded");
        require(recent.get(0).checkedAt().isAfter(recent.get(recent.size() - 1).checkedAt()), "receipts are newest first");
        require(bounded.recent(TENANT, SOURCE, 1).size() == 1, "the store keeps the scope bound");
        require(bounded.kept(TENANT, SOURCE) == SourceConnectionCheck.MAX_KEPT, "only the newest receipts are kept");
        require(bounded.recent(OTHER, SOURCE, 10).isEmpty(), "another tenant sees no receipts");
        require(bounded.recent(TENANT, "zabbix-2", 10).isEmpty(), "another source sees no receipts");
        fails(SourceConnectionCheckException.Code.INVALID_REQUEST, () -> boundedService.recent(READER, 0));
        fails(SourceConnectionCheckException.Code.INVALID_REQUEST, () -> boundedService.recent(READER, 51));
        fails(SourceConnectionCheckException.Code.FORBIDDEN, () -> boundedService.recent(
            new Principal(new SubjectId("operator"), TENANT, Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide()), 10));

        System.out.println("SourceConnectionCheckSmoke: " + checks + " checks passed");
    }

    private static SourceConnectionCheckService service(Connector connector, InMemorySourceConnectionCheckStore store, Clock clock) {
        return new SourceConnectionCheckService(new AuthorizeUseCase(), connector, store, SOURCE, "labeled-fixture", "env:OPSWEAVE_ZABBIX_TOKEN", clock);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
        checks++;
    }

    private static void fails(SourceConnectionCheckException.Code code, Runnable action) {
        try {
            action.run();
        } catch (SourceConnectionCheckException expected) {
            require(expected.code() == code, "expected " + code + " but got " + expected.code());
            return;
        }
        throw new IllegalStateException("Expected " + code);
    }

    private static final class ReportingConnector implements Connector {
        private final boolean reachable;
        private final String version;

        private ReportingConnector(boolean reachable, String version) {
            this.reachable = reachable;
            this.version = version;
        }

        @Override public String type() { return "zabbix"; }
        @Override public ProbeResult probe(SourceContext source) {
            return reachable ? new ProbeResult(true, "ok", version) : new ProbeResult(false, "unreachable");
        }
        @Override public Page fetch(SourceContext source, String cursor, int limit) {
            throw new IllegalStateException("A self-check never fetches pages");
        }
    }

    private static final class ThrowingConnector implements Connector {
        @Override public String type() { return "zabbix"; }
        @Override public ProbeResult probe(SourceContext source) { throw new IllegalStateException("vendor detail"); }
        @Override public Page fetch(SourceContext source, String cursor, int limit) {
            throw new IllegalStateException("A self-check never fetches pages");
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
