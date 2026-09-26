import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;

public class PipelineDraftSmoke {
    private static int checks;
    private static void check(boolean valid) { checks++; if (!valid) throw new AssertionError("Check " + checks); }
    private static void fail(PipelineException.Code code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (PipelineException expected) { check(expected.code() == code); }
    }
    public static void main(String[] args) {
        var auth = new AuthorizeUseCase(); var store = new InMemoryPipelineDraftStore();
        var clock = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC);
        var service = new PipelineDraftService(auth, store, "zabbix-1", clock);
        var tenant = new TenantId("draft-tenant"); var owner = new SubjectId("alice");
        var principal = new Principal(owner, tenant, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide());
        var definition = PipelineDefinition.zabbixHostV1();
        var first = service.save(principal, definition, 0);
        check(first.editVersion() == 1); check(first.content().equals(PipelineVersion.of(definition)));
        check(service.get(principal, definition.id(), 1).equals(first));
        fail(PipelineException.Code.DRAFT_CONFLICT, () -> service.save(principal, definition, 0));
        var changed = new PipelineDefinition(definition.id(), 1, "zabbix", "host", definition.nodes(), definition.edges(), ErrorPolicy.FAIL_FAST);
        var second = service.save(principal, changed, 1);
        check(second.editVersion() == 2); check(!second.content().digest().equals(first.content().digest()));
        fail(PipelineException.Code.DRAFT_CONFLICT, () -> service.save(principal, definition, 1));
        check(service.get(principal, definition.id(), 1).equals(second));
        var other = new Principal(new SubjectId("bob"), tenant, principal.permissions(), principal.resourceScope());
        fail(PipelineException.Code.NOT_FOUND, () -> service.get(other, definition.id(), 1));
        check(service.list(other, 20).items().isEmpty());
        check(service.save(other, definition, 0).editVersion() == 1);
        check(store.find(new TenantId("other"), "zabbix-1", owner, definition.id(), 1).isEmpty());
        check(store.find(tenant, "other", owner, definition.id(), 1).isEmpty());
        var denied = new Principal(owner, tenant, Set.of(), ResourceScope.tenantWide());
        fail(PipelineException.Code.FORBIDDEN, () -> service.save(denied, definition, 0));
        fail(PipelineException.Code.FORBIDDEN, () -> service.get(denied, definition.id(), 1));
        fail(PipelineException.Code.FORBIDDEN, () -> service.list(denied, 20));
        fail(PipelineException.Code.INVALID_REQUEST, () -> service.save(principal, definition, -1));
        fail(PipelineException.Code.INVALID_REQUEST, () -> service.save(principal, definition, Integer.MAX_VALUE));
        fail(PipelineException.Code.INVALID_REQUEST, () -> service.list(principal, 51));
        fail(PipelineException.Code.INVALID_REQUEST, () -> service.get(principal, "../bad", 1));
        for (int revision = 2; revision <= 4; revision++) service.save(principal,
            new PipelineDefinition(definition.id(), revision, "zabbix", "host", definition.nodes(), definition.edges(), definition.errorPolicy()), 0);
        var recent = service.list(principal, 2);
        check(recent.truncated()); check(recent.items().size() == 2);
        check(recent.items().getFirst().target().revision() == 4); check(recent.items().getLast().target().revision() == 3);
        check(!service.list(principal, 50).truncated());
        var versions = new InMemoryPipelineVersionStore(); versions.publish(tenant, "zabbix-1", first.content());
        service.save(principal, definition, 2);
        check(versions.find(tenant, "zabbix-1", definition.id(), 1).orElseThrow().equals(first.content()));
        System.out.println("Pipeline draft smoke: " + checks + " checks passed");
    }
}
