import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.catalog.domain.ModelDefinition.*;
import com.acme.opsweave.catalog.application.ModelCatalogService;
import com.acme.opsweave.catalog.infrastructure.InMemoryModelCatalogStore;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ModelCatalogSmoke {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Model catalog check " + checks); }
    static void rejects(Runnable call) { checks++; try { call.run(); } catch (IllegalArgumentException | CatalogFailure expected) { return; } throw new AssertionError("Expected rejection " + checks); }
    static Field text(String id, boolean required) { return new Field(id, id, Type.TEXT, required, 255, null, null, List.of()); }
    static ModelDefinition model(String id, int version, List<Field> fields) { return new ModelDefinition(id, version, Kind.ENTITY, "Test model", "explicit fixture", fields, null); }
    static Principal principal(String tenant, String actor, Set<Permission> permissions, ResourceScope scope) { return new Principal(new SubjectId(actor), new TenantId(tenant), permissions, scope); }
    public static void main(String[] args) {
        var fields = List.of(text("name", true), new Field("port", "Port", Type.INTEGER, true, null, BigDecimal.ONE, new BigDecimal("65535"), List.of()),
            new Field("enabled", "Enabled", Type.BOOLEAN, false, null, null, null, List.of()), new Field("seen", "Seen", Type.DATETIME, false, null, null, null, List.of()));
        var d = model("custom.service", 1, fields);
        var valid = ModelPreview.evaluate(d, Map.of("name", " svc ", "port", " 8080 ", "enabled", "true", "seen", "2026-09-27T12:00:00+08:00"));
        check(valid.valid()); check(valid.values().get("name").equals("svc")); check(((BigDecimal) valid.values().get("port")).compareTo(new BigDecimal("8080")) == 0); check(valid.values().get("seen").equals("2026-09-27T04:00:00Z")); check(valid.values().get("enabled").equals(true)); check(valid.changes().size() == 4);
        for (Object bad : List.of("1.1", "01", "0x10", "1e3", true, "9007199254740992", "NaN", "Infinity", "65536", "0")) check(!ModelPreview.evaluate(d, Map.of("name", "ok", "port", bad)).valid());
        check(ModelPreview.evaluate(d, Map.of("name", "ok", "port", new BigDecimal("1E+3"))).valid());
        rejects(() -> ModelPreview.evaluate(d, Map.of("", "empty key")));
        check(ModelPreview.evaluate(d, Map.of()).issues().stream().allMatch(i -> i.code().equals("MISSING_REQUIRED")));
        var nulls = new HashMap<String,Object>(); nulls.put("name", null); nulls.put("port", "5"); nulls.put("enabled", null);
        var result = ModelPreview.evaluate(d, nulls); check(!result.valid()); check(result.issues().getFirst().code().equals("NULL_REQUIRED")); check(result.values().containsKey("enabled")); check(result.values().get("enabled") == null);
        check(!ModelPreview.evaluate(d, Map.of("name", " ", "port", 5)).valid());
        check(ModelPreview.evaluate(d, Map.of("name", "ok", "port", 5, "tenant_id", "other")).issues().stream().anyMatch(i -> i.code().equals("UNKNOWN_FIELD")));
        check(!ModelPreview.evaluate(d, Map.of("name", "ok", "port", 5, "enabled", "1")).valid());
        check(!ModelPreview.evaluate(d, Map.of("name", "ok", "port", 5, "seen", "2026-09-27T12:00:00")).valid());
        rejects(() -> ModelPreview.evaluate(d, Map.of("name", Map.of("nested", "no"))));
        rejects(() -> model("custom.x", 1, List.of(text("tenant_id", false))));
        rejects(() -> model("custom.x", 1, List.of(text("name", false), text("name", false))));
        rejects(() -> model("https://example.com/schema", 1, fields));
        rejects(() -> new Field("bad", "bad", Type.TEXT, false, 0, null, null, List.of()));
        rejects(() -> new Field("bad", "bad", Type.INTEGER, false, null, BigDecimal.TEN, BigDecimal.ONE, List.of()));
        var reversed = new ArrayList<>(fields); Collections.reverse(reversed); check(d.digest().equals(model(d.id(), 1, reversed).digest()));
        var store = new InMemoryModelCatalogStore(); var clock = Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC);
        var service = new ModelCatalogService(store, List.of(), clock);
        var p = principal("catalog-a", "one", Set.of(Permission.ENTITY_READ, Permission.ENTITY_MANAGE), ResourceScope.tenantWide());
        var draft = service.save(p, d, 0); check(draft.editVersion() == 1);
        rejects(() -> service.save(p, d, 0)); rejects(() -> service.publish(p, d.ref(), 2, d.digest()));
        var otherOwner = principal("catalog-a", "two", p.permissions(), p.resourceScope()); check(service.drafts(otherOwner).items().isEmpty()); rejects(() -> service.publish(otherOwner, d.ref(), 1, d.digest()));
        var published = service.publish(p, d.ref(), 1, d.digest()); check(published.state().equals("PUBLISHED")); check(service.publish(p, d.ref(), 1, d.digest()).equals(published));
        check(service.find(otherOwner, d.ref()).digest().equals(d.digest())); rejects(() -> service.save(p, d, 1));
        var otherTenant = principal("catalog-b", "one", p.permissions(), p.resourceScope()); check(service.published(otherTenant).items().isEmpty()); rejects(() -> service.find(otherTenant, d.ref()));
        var restricted = principal("catalog-a", "three", p.permissions(), ResourceScope.of(Set.of(ResourceRef.anyEntity(p.tenantId())))); rejects(() -> service.published(restricted)); rejects(() -> service.save(restricted, d, 0));
        var readOnly = principal("catalog-a", "four", Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide()); check(service.published(readOnly).items().size() == 1); rejects(() -> service.save(readOnly, d, 0));
        var add = new ArrayList<>(fields); add.add(text("owner", false)); var next = model(d.id(), 2, add); service.save(p, next, 0); check(service.publish(p, next.ref(), 1, next.digest()).definition().revision() == 2);
        rejects(() -> model(d.id(), 3, List.of()).requireCompatibleWith(next));
        var requiredAdd = new ArrayList<>(add); requiredAdd.add(text("required_new", true)); rejects(() -> model(d.id(), 3, requiredAdd).requireCompatibleWith(next));
        var relation = new ModelDefinition("custom.deployed", 1, Kind.RELATION, "Deployed", "", List.of(), new Endpoints(d.ref(), next.ref(), Cardinality.MANY_TO_MANY)); service.save(p, relation, 0); check(service.publish(p, relation.ref(), 1, relation.digest()).state().equals("PUBLISHED"));
        var dangling = new ModelDefinition("custom.dangling", 1, Kind.RELATION, "Dangling", "", List.of(), new Endpoints(d.ref(), new Ref("custom.unknown", 1), Cardinality.ONE_TO_ONE)); service.save(p, dangling, 0); rejects(() -> service.publish(p, dangling.ref(), 1, dangling.digest()));
        rejects(() -> service.save(p, model("builtin.host", 1, fields), 0));
        System.out.println("ModelCatalogSmoke: " + checks + " checks passed");
    }
}
