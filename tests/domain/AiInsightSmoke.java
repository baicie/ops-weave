import com.acme.opsweave.aicontrol.api.*;
import com.acme.opsweave.aicontrol.application.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.aicontrol.infrastructure.*;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.incident.infrastructure.InMemoryIncidentStore;
import com.acme.opsweave.sharedkernel.*;
import com.acme.opsweave.telemetry.application.QueryMetricSeriesUseCase;
import com.acme.opsweave.telemetry.infrastructure.*;
import java.time.*;
import java.util.*;
import static com.acme.opsweave.aicontrol.domain.ToolFailure.Code.*;

public class AiInsightSmoke {
    static int checks;
    static void check(boolean b) { checks++; if (!b) throw new AssertionError("check " + checks); }
    static void fails(ToolFailure.Code code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (ToolFailure failure) { check(failure.code() == code); } }
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-25T01:00:00Z"); public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId z) { return this; } public Instant instant() { return now; }
    }
    public static void main(String[] args) {
        var clock = new TestClock(); var tenant = new TenantId("insight-domain"); var user = new SubjectId("reader");
        var p = new Principal(user, tenant, Set.of(Permission.AI_DIAGNOSE, Permission.AI_INSIGHT_READ, Permission.INCIDENT_READ, Permission.ENTITY_READ, Permission.METRIC_READ, Permission.EVIDENCE_READ), ResourceScope.tenantWide());
        var auth = new AuthorizeUseCase(); var incidents = new InMemoryIncidentStore((t,s,h) -> Map.of());
        var observation = new ExternalProblem(tenant, "zabbix-1", "101", "201", "Fixture incident", 3, clock.now.minusSeconds(30), clock.now, List.of(), false, null, null);
        incidents.ingest(tenant, "zabbix-1", "labeled-fixture", List.of(observation), clock.now); var id = IncidentRecord.initialId(observation);
        var incidentService = new IncidentService(auth, incidents, clock); var tools = new InMemoryToolReadStore(); var catalog = new InMemoryMetricDefinitionStore();
        var gateway = new ToolGateway(auth, incidentService, new QueryMetricSeriesUseCase(auth, (t,e) -> true, catalog, new ClosedMetricQuery(), clock), catalog, tools, e -> 1000, clock);
        var store = new InMemoryAiInsightStore(incidents, tools, clock);
        var encoding = new InsightEncoding() {
            public String digest(InsightSubmission s) { return "sha256:" + (s.question().equals("changed") ? "b" : "a").repeat(64); }
            public int bytes(AiInsight result) { return 4000; }
        };
        var service = new AiInsightService(auth, incidentService, gateway, tools, store, encoding, clock);
        var window = new ToolWindow(clock.now.minusSeconds(600), clock.now); var session = gateway.create(p, id, window);
        var incident = gateway.incident(p, session.id(), id); var metric = gateway.metric(p, session.id(), id, "missing.metric", window, 500);
        var draft = new InsightDraft("Available evidence only", List.of(new InsightDraft.Finding("observation", "Fixture observation", List.of(incident.id()))), List.of(), List.of());
        var submission = new InsightSubmission(UUID.randomUUID(), session.id(), "Summarize", clock.now, clock.now, clock.now,
            new InsightSubmission.SkillRef("incident.diagnose", "2.0.0", "sha256:" + "c".repeat(64)), new InsightSubmission.ModelRef("mock-deterministic", "mock-current-v1"), List.of(incident.id(), metric.id()), draft);
        fails(INVALID_REQUEST, () -> service.submit(p, submission));
        gateway.recheck(p, session.id(), incident.id()); gateway.recheck(p, session.id(), metric.id());
        var saved = service.submit(p, submission); check(saved.tenantId().equals(tenant)); check(saved.subjectId().equals(user));
        check(saved.input().insight().missingData().contains("METRIC_NOT_FOUND")); check(saved.input().insight().missingData().contains("LOGS_NOT_CONNECTED"));
        check(saved.input().insight().limitations().stream().anyMatch(s -> s.contains("no model inference")));
        check(service.submit(p, submission).equals(saved)); check(service.get(p, submission.runId()).equals(saved));
        var altered = new InsightSubmission(submission.runId(), session.id(), "changed", clock.now, clock.now, clock.now, submission.skill(), submission.model(), submission.evidenceIds(), draft);
        fails(INPUT_CHANGED, () -> service.submit(p, altered));
        var newRun = new InsightSubmission(UUID.randomUUID(), session.id(), "Summarize", clock.now, clock.now, clock.now, submission.skill(), submission.model(), submission.evidenceIds(), draft);
        fails(INPUT_CHANGED, () -> service.submit(p, newRun));
        var other = new Principal(new SubjectId("other"), tenant, p.permissions(), p.resourceScope());
        fails(INPUT_CHANGED, () -> service.submit(other, submission)); check(service.get(other, submission.runId()).equals(saved));
        var denied = new Principal(user, tenant, Set.of(Permission.AI_DIAGNOSE), ResourceScope.tenantWide()); fails(FORBIDDEN, () -> service.get(denied, submission.runId()));
        var cross = new Principal(user, new TenantId("other"), p.permissions(), ResourceScope.tenantWide()); fails(NOT_FOUND, () -> service.get(cross, submission.runId()));
        fails(INVALID_REQUEST, () -> new InsightDraft("bad", List.of(new InsightDraft.Finding("action", "restart", List.of(incident.id()))), List.of(), List.of()));
        fails(INVALID_REQUEST, () -> new InsightSubmission(UUID.randomUUID(), session.id(), "q", clock.now, clock.now, clock.now, submission.skill(), submission.model(), submission.evidenceIds(),
            new InsightDraft("forged", List.of(new InsightDraft.Finding("hypothesis", "unproved", List.of(UUID.randomUUID()))), List.of(), List.of())));
        clock.now = clock.now.plusSeconds(61); check(service.submit(p, submission).equals(saved));
        clock.now = clock.now.plusSeconds(86400); fails(EXPIRED, () -> service.get(p, submission.runId()));
        System.out.println("AIInsight smoke: " + checks + " checks passed");
    }
}
