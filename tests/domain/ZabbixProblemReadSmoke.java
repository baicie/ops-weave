import com.acme.opsweave.alerting.domain.*;
import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.integration.application.ReadZabbixProblemsUseCase;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.net.URI;
import java.time.*;
import java.util.*;

public class ZabbixProblemReadSmoke {
    private static int checks;
    private static final Instant NOW = Instant.ofEpochSecond(1000);
    private static final Connector.SourceContext SOURCE = new Connector.SourceContext(new TenantId("problem-tenant"), "zabbix-1", "env:TEST");
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Check " + checks); }
    private static void fail(ProblemReadException.Code code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (ProblemReadException e) { check(e.code() == code); }
    }
    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected invalid argument"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static Map<String,Object> problem(String event, String recovery) {
        var row = new LinkedHashMap<String,Object>();
        row.put("eventid", event); row.put("source", "0"); row.put("object", "0"); row.put("objectid", "50");
        row.put("clock", "900"); row.put("ns", "42"); row.put("value", "1"); row.put("name", "CPU problem");
        row.put("severity", "4"); row.put("r_eventid", recovery); row.put("suppressed", "0");
        row.put("hosts", List.of(Map.of("hostid", "10084"))); return row;
    }
    private static Map<String,Object> recovery(String id) { return Map.of("eventid", id, "source", "0", "object", "0", "value", "0", "clock", "950", "ns", "7"); }
    private static class Transport implements ZabbixJsonRpcConnector.Transport {
        List<Map<String,Object>> problems = List.of(problem("101", "201"));
        List<Map<String,Object>> recoveries = List.of(recovery("201"));
        List<String> requests = new ArrayList<>(); boolean fail;
        public String exchange(URI endpoint, String json, String token) {
            requests.add(json); if (fail) throw new IllegalStateException("private upstream details");
            return json.contains("\"eventids\"") ? "recovery" : "problem";
        }
        public List<Map<String,Object>> readHostArray(String body) { return body.equals("problem") ? problems : recoveries; }
    }
    private static ZabbixJsonRpcProblemReader reader(Transport transport) {
        return new ZabbixJsonRpcProblemReader(URI.create("http://127.0.0.1/api_jsonrpc.php"), transport, ref -> "test-secret", Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static ZabbixProblemPort.Page read(Transport t) { return reader(t).read(SOURCE, new ProblemReadWindow(800, 1000, null, 10)); }
    public static void main(String[] args) {
        var t = new Transport(); var page = read(t); var recovered = page.items().getFirst();
        check(recovered.state() == ExternalProblem.State.RECOVERED); check(recovered.recoveredAt().equals(Instant.ofEpochSecond(950, 7)));
        check(recovered.occurredAt().equals(Instant.ofEpochSecond(900, 42))); check(recovered.tenantId().equals(SOURCE.tenantId()));
        check(recovered.hostIds().equals(List.of("10084"))); check(page.nextAfterEventId() == null);
        check(t.requests.size() == 2); check(t.requests.getFirst().contains("\"problem_time_from\":800"));
        check(t.requests.getLast().contains("\"eventids\":[\"201\"]")); check(t.requests.stream().noneMatch(r -> r.contains("test-secret")));
        t = new Transport(); t.problems = List.of(problem("101", "0"));
        var active = read(t).items().getFirst(); check(active.state() == ExternalProblem.State.ACTIVE); check(t.requests.size() == 1);
        t.problems = List.of(problem("101", "201")); t.recoveries = List.of();
        check(read(t).items().getFirst().state() == ExternalProblem.State.RECOVERY_UNKNOWN);
        t = new Transport(); t.problems = List.of(problem("102", "0"));
        var next = reader(t).read(SOURCE, new ProblemReadWindow(800, 1000, "101", 1));
        check(next.nextAfterEventId().equals("102")); check(t.requests.getFirst().contains("\"eventid_from\":\"102\""));
        var empty = reader(t).read(SOURCE, new ProblemReadWindow(800, 1000, "18446744073709551615", 10));
        check(empty.items().isEmpty()); check(t.requests.size() == 1);
        var duplicate = new Transport(); duplicate.problems = List.of(problem("101", "0"), problem("101", "0"));
        fail(ProblemReadException.Code.INVALID_SOURCE_RESPONSE, () -> read(duplicate));
        var oversize = new Transport(); oversize.problems = Collections.nCopies(11, problem("101", "0"));
        fail(ProblemReadException.Code.INVALID_SOURCE_RESPONSE, () -> read(oversize));
        var wrongRecovery = new Transport(); wrongRecovery.recoveries = List.of(recovery("202"));
        fail(ProblemReadException.Code.INVALID_SOURCE_RESPONSE, () -> read(wrongRecovery));
        var broken = new Transport(); broken.fail = true;
        fail(ProblemReadException.Code.SOURCE_FETCH_FAILED, () -> read(broken)); check(broken.requests.size() == 1);
        for (var change : List.of(Map.entry("source", "1"), Map.entry("value", "0"), Map.entry("r_eventid", "garbage"),
            Map.entry("severity", "6"), Map.entry("suppressed", "yes"), Map.entry("ns", "1000000000"), Map.entry("clock", "1001"),
            Map.entry("eventid", "18446744073709551616"), Map.entry("c_eventid", "501"), Map.entry("name", "x".repeat(301)))) {
            var bad = new Transport(); var row = problem("101", "0"); row.put(change.getKey(), change.getValue()); bad.problems = List.of(row);
            fail(ProblemReadException.Code.INVALID_SOURCE_RESPONSE, () -> read(bad));
        }
        var forged = new Transport(); var row = problem("101", "0"); row.put("tenantId", "attacker"); row.put("permissions", List.of("admin")); forged.problems = List.of(row);
        check(read(forged).items().getFirst().tenantId().equals(SOURCE.tenantId()));
        invalid(() -> new ProblemReadWindow(0, 86401, null, 10)); invalid(() -> new ProblemReadWindow(20, 10, null, 10));
        invalid(() -> new ProblemReadWindow(0, 1, "01", 10)); invalid(() -> new ProblemReadWindow(0, 1, null, 101));
        invalid(() -> reader(new Transport()).read(SOURCE, new ProblemReadWindow(999, 1001, null, 10)));
        invalid(() -> ProblemObservations.merge(active, recovered)); // same receipt timestamp but conflicting observations
        var later = new ExternalProblem(recovered.tenantId(), recovered.sourceInstanceId(), recovered.problemEventId(), recovered.triggerId(), recovered.title(),
            recovered.severity(), recovered.occurredAt(), NOW.plusSeconds(1), recovered.hostIds(), true, recovered.recoveryEventId(), recovered.recoveredAt());
        var merged = ProblemObservations.merge(active, later); check(merged.equals(later)); check(ProblemObservations.merge(merged, active).equals(merged));
        var staleActive = new ExternalProblem(active.tenantId(), active.sourceInstanceId(), active.problemEventId(), active.triggerId(), active.title(),
            active.severity(), active.occurredAt(), NOW.plusSeconds(2), active.hostIds(), false, null, null);
        check(ProblemObservations.merge(merged, staleActive).state() == ExternalProblem.State.RECOVERED);
        check(ProblemObservations.merge(active, active).equals(active));
        var allow = new Principal(new SubjectId("operator"), SOURCE.tenantId(), Set.of(Permission.SOURCE_SYNC), ResourceScope.of(Set.of(ResourceRef.source(SOURCE.tenantId(), SOURCE.sourceInstanceId()))));
        var window = new ProblemReadWindow(800, 1000, null, 10);
        var service = new ReadZabbixProblemsUseCase(new AuthorizeUseCase(), reader(new Transport()), SOURCE.sourceInstanceId(), SOURCE.secretRef(), "zabbix-jsonrpc", Clock.fixed(NOW, ZoneOffset.UTC));
        check(service.execute(allow, window).page().items().size() == 1);
        var denied = new Principal(allow.subjectId(), allow.tenantId(), Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide());
        fail(ProblemReadException.Code.FORBIDDEN, () -> service.execute(denied, window));
        var wrongSource = new Principal(allow.subjectId(), allow.tenantId(), allow.permissions(), ResourceScope.of(Set.of(ResourceRef.source(SOURCE.tenantId(), "other"))));
        fail(ProblemReadException.Code.FORBIDDEN, () -> service.execute(wrongSource, window));
        var closed = new ReadZabbixProblemsUseCase(new AuthorizeUseCase(), null, SOURCE.sourceInstanceId(), SOURCE.secretRef(), "closed", Clock.fixed(NOW, ZoneOffset.UTC));
        fail(ProblemReadException.Code.SOURCE_UNAVAILABLE, () -> closed.execute(allow, window));
        var wrongTenant = new ExternalProblem(new TenantId("foreign"), active.sourceInstanceId(), active.problemEventId(), active.triggerId(), active.title(),
            active.severity(), active.occurredAt(), active.observedAt(), active.hostIds(), false, null, null);
        var scoped = new ReadZabbixProblemsUseCase(new AuthorizeUseCase(), (s, w) -> new ZabbixProblemPort.Page(List.of(wrongTenant), null), SOURCE.sourceInstanceId(), SOURCE.secretRef(), "zabbix-jsonrpc", Clock.fixed(NOW, ZoneOffset.UTC));
        fail(ProblemReadException.Code.INVALID_SOURCE_RESPONSE, () -> scoped.execute(allow, window));
        System.out.println("Zabbix problem read smoke: " + checks + " checks passed");
    }
}
