import com.acme.opsweave.catalog.api.ModelExtensionStore;
import com.acme.opsweave.catalog.application.ModelExtensionService;
import com.acme.opsweave.catalog.application.ModelCatalogService;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.catalog.infrastructure.InMemoryModelCatalogStore;
import com.acme.opsweave.catalog.infrastructure.InMemoryModelExtensionStore;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Tenant extension overlays: optional bounded fields over one immutable base model version. */
public final class ModelExtensionSmoke {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Model extension check " + checks); }
    static void rejects(Runnable call) { checks++; try { call.run(); } catch (IllegalArgumentException | CatalogFailure expected) { return; } throw new AssertionError("Expected rejection " + checks); }
    static ModelDefinition.Field text(String id) { return new ModelDefinition.Field(id, id, ModelDefinition.Type.TEXT, false, 64, null, null, List.of()); }
    static ModelDefinition baseModel(int revision, List<ModelDefinition.Field> fields) {
        return new ModelDefinition("builtin.host", revision, ModelDefinition.Kind.ENTITY, "Host", "explicit fixture", fields, null);
    }
    static Principal principal(String tenant, String actor, Set<Permission> permissions, ResourceScope scope) {
        return new Principal(new SubjectId(actor), new TenantId(tenant), permissions, scope);
    }

    public static void main(String[] args) {
        var baseFields = List.of(new ModelDefinition.Field("hostname", "Hostname", ModelDefinition.Type.TEXT, true, 255, null, null, List.of()),
            new ModelDefinition.Field("ip", "IP", ModelDefinition.Type.TEXT, false, 255, null, null, List.of()));
        var base = baseModel(1, baseFields);
        var businessCode = text("business_code");
        var criticality = new ModelDefinition.Field("criticality", "Criticality", ModelDefinition.Type.ENUM, false, 32, null, null, List.of("high", "medium", "low"));

        // Shape: extensions only add optional fields, never reserved or duplicate identities.
        var extension = new ModelExtension(base.ref(), 1, List.of(businessCode, criticality));
        check(extension.fields().size() == 2);
        rejects(() -> new ModelExtension(base.ref(), 1, List.of(new ModelDefinition.Field("required_new", "R", ModelDefinition.Type.TEXT, true, 32, null, null, List.of()))));
        rejects(() -> new ModelExtension(base.ref(), 1, List.of(businessCode, text("business_code"))));
        rejects(() -> new ModelExtension(base.ref(), 1, List.of()));
        rejects(() -> new ModelExtension(base.ref(), 0, List.of(businessCode)));
        rejects(() -> new ModelExtension(base.ref(), 10001, List.of(businessCode)));
        rejects(() -> new ModelExtension(base.ref(), 1, List.of(text("tenant_id"))));
        rejects(() -> new ModelExtension(base.ref(), 1, List.of(new ModelDefinition.Field("bad", "Bad", ModelDefinition.Type.TEXT, false, 0, null, null, List.of()))));

        // Digest: stable under field order, sensitive to meaning, fixed per base/revision.
        check(extension.digest().equals(new ModelExtension(base.ref(), 1, List.of(criticality, businessCode)).digest()));
        check(!extension.digest().equals(new ModelExtension(base.ref(), 2, List.of(businessCode, criticality)).digest()));
        check(!extension.digest().equals(new ModelExtension(base.ref(), 1, List.of(businessCode)).digest()));
        check(extension.digest().matches("sha256:[a-f0-9]{64}"));

        // The base body is immutable: extension fields may not collide or overflow the field budget.
        extension.requireCompatibleBase(base);
        rejects(() -> new ModelExtension(base.ref(), 1, List.of(text("hostname"))).requireCompatibleBase(base));
        var relationBase = new ModelDefinition("custom.deployed", 1, ModelDefinition.Kind.RELATION, "Deployed", "", List.of(),
            new ModelDefinition.Endpoints(base.ref(), base.ref(), ModelDefinition.Cardinality.MANY_TO_MANY));
        rejects(() -> new ModelExtension(relationBase.ref(), 1, List.of(businessCode)).requireCompatibleBase(relationBase));
        var many = new ArrayList<ModelDefinition.Field>();
        for (int i = 0; i < 31; i++) many.add(text("extra_" + i));
        rejects(() -> new ModelExtension(base.ref(), 1, many).requireCompatibleBase(base));
        rejects(() -> new ModelExtension(new ModelDefinition.Ref("custom.other", 1), 1, List.of(businessCode)).requireCompatibleBase(base));

        // Entity writes validate the effective model: optional extension fields, unknown still rejected.
        var effective = extension.appliedTo(base);
        var valid = ModelPreview.evaluate(effective, Map.of("hostname", "edge-01", "business_code", " B-1 ", "criticality", "high"));
        check(valid.valid());
        check(valid.values().get("business_code").equals("B-1"));
        check(!ModelPreview.evaluate(effective, Map.of("hostname", "edge-01", "criticality", "urgent")).valid());
        check(!ModelPreview.evaluate(effective, Map.of("hostname", "edge-01", "unknown_field", "x")).valid());
        check(!ModelPreview.evaluate(effective, Map.of("business_code", "B-1")).valid());
        check(ModelPreview.evaluate(base, Map.of("hostname", "edge-01")).valid());

        // Published revisions are consecutive and additive only.
        var revised = new ModelExtension(base.ref(), 2, List.of(businessCode, criticality, text("owner")));
        revised.requireCompatibleWith(extension);
        rejects(() -> new ModelExtension(base.ref(), 2, List.of(businessCode)).requireCompatibleWith(extension));
        rejects(() -> new ModelExtension(base.ref(), 2, List.of(
            new ModelDefinition.Field("business_code", "Renamed", ModelDefinition.Type.INTEGER, false, null, null, null, List.of()), criticality)).requireCompatibleWith(extension));
        rejects(() -> new ModelExtension(base.ref(), 3, List.of(businessCode, criticality, text("owner"))).requireCompatibleWith(extension));
        var relabelled = new ModelExtension(base.ref(), 2, List.of(
            new ModelDefinition.Field("business_code", "业务编码", ModelDefinition.Type.TEXT, false, 64, null, null, List.of()), criticality));
        relabelled.requireCompatibleWith(extension);

        // Service and store: tenant namespace, owner-private drafts, CAS and immutable publications.
        var clock = Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC);
        var extensions = new InMemoryModelExtensionStore();
        var catalog = new InMemoryModelCatalogStore();
        var service = new ModelExtensionService(extensions, catalog, List.of(base), clock);
        var models = new ModelCatalogService(catalog, List.of(), clock);
        var p = principal("extension-a", "one", Set.of(Permission.ENTITY_READ, Permission.ENTITY_MANAGE), ResourceScope.tenantWide());
        var draft = service.save(p, base.ref(), List.of(businessCode), 0);
        check(draft.state().equals("DRAFT") && draft.editVersion() == 1 && draft.extension().revision() == 1);
        check(draft.extension().digest().equals(new ModelExtension(base.ref(), 1, List.of(businessCode)).digest()));
        rejects(() -> service.save(p, base.ref(), List.of(businessCode), 0));
        rejects(() -> service.save(p, base.ref(), List.of(businessCode), 5));
        rejects(() -> service.publish(p, base.ref(), 2, draft.extension().digest()));
        rejects(() -> service.publish(p, base.ref(), 1, "sha256:" + "a".repeat(64)));
        var published = service.publish(p, base.ref(), 1, draft.extension().digest());
        check(published.state().equals("PUBLISHED") && published.editVersion() == 0 && published.extension().revision() == 1);
        check(service.publish(p, base.ref(), 1, draft.extension().digest()).equals(published));
        check(service.published(p).items().size() == 1);
        check(extensions.latest(p.tenantId(), base.ref()).orElseThrow().extension().digest().equals(published.extension().digest()));

        // Second revision stays additive; incompatible revisions never publish.
        var second = service.save(p, base.ref(), List.of(businessCode, criticality), 1);
        check(second.extension().revision() == 2 && second.editVersion() == 2);
        check(service.publish(p, base.ref(), 2, second.extension().digest()).extension().revision() == 2);
        var dropping = service.save(p, base.ref(), List.of(text("owner")), 2);
        check(dropping.extension().revision() == 3);
        rejects(() -> service.publish(p, base.ref(), 3, dropping.extension().digest()));
        check(extensions.latest(p.tenantId(), base.ref()).orElseThrow().extension().revision() == 2);

        // Field identity rules hold through the service: base collisions and duplicates are rejected.
        rejects(() -> service.save(p, base.ref(), List.of(text("hostname")), 3));
        rejects(() -> service.save(p, base.ref(), List.of(businessCode, text("business_code")), 3));
        rejects(() -> service.save(p, base.ref(), List.of(new ModelDefinition.Field("required_new", "R", ModelDefinition.Type.TEXT, true, 32, null, null, List.of())), 3));

        // Drafts are owner-private and tenant-namespaced; readers and object scopes cannot manage.
        var otherOwner = principal("extension-a", "two", p.permissions(), p.resourceScope());
        check(service.drafts(otherOwner).items().isEmpty());
        var otherTenant = principal("extension-b", "one", p.permissions(), p.resourceScope());
        check(service.published(otherTenant).items().isEmpty());
        check(extensions.find(otherTenant.tenantId(), base.ref(), 2).isEmpty());
        check(extensions.drafts(otherTenant.tenantId(), "one", 51).isEmpty());
        // A second tenant declares its own overlay in its own namespace instead of reusing tenant A's.
        check(service.save(otherTenant, base.ref(), List.of(businessCode), 0).extension().revision() == 1);
        check(service.published(otherTenant).items().isEmpty());
        check(service.published(p).items().size() == 2);
        check(extensions.drafts(otherTenant.tenantId(), "one", 51).size() == 1);
        check(extensions.drafts(otherTenant.tenantId(), "one", 51).getFirst().extension().revision() == 1);
        var readOnly = principal("extension-a", "three", Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide());
        check(service.published(readOnly).items().size() == 2);
        rejects(() -> service.save(readOnly, base.ref(), List.of(text("owner")), 3));
        var restricted = principal("extension-a", "four", p.permissions(), ResourceScope.of(Set.of(ResourceRef.anyEntity(p.tenantId()))));
        rejects(() -> service.save(restricted, base.ref(), List.of(text("owner")), 3));
        rejects(() -> service.published(restricted));

        // Custom bases must already be published inside the tenant; built-in bodies stay untouched.
        var custom = new ModelDefinition("custom.widget", 1, ModelDefinition.Kind.ENTITY, "Widget", "explicit fixture", List.of(text("name")), null);
        rejects(() -> service.save(p, custom.ref(), List.of(text("serial")), 0));
        models.save(p, custom, 0);
        rejects(() -> service.save(p, custom.ref(), List.of(text("serial")), 0));
        models.publish(p, custom.ref(), 1, custom.digest());
        check(service.save(p, custom.ref(), List.of(text("serial")), 0).extension().revision() == 1);
        rejects(() -> service.save(p, base.ref(), List.of(businessCode), 2));
        check(base.fields().equals(baseFields));

        System.out.println("ModelExtensionSmoke: " + checks + " checks passed");
    }
}
