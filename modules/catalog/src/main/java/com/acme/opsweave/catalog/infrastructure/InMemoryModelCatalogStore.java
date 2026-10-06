package com.acme.opsweave.catalog.infrastructure;

import com.acme.opsweave.catalog.api.ModelCatalogStore;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

/** Explicit ephemeral store only when inventory.store=memory; never a PostgreSQL fallback. */
public final class InMemoryModelCatalogStore implements ModelCatalogStore {
    private record Key(TenantId tenant, String owner, ModelDefinition.Ref ref) {}
    private final Map<Key, Entry> drafts = new HashMap<>(), versions = new HashMap<>();
    public synchronized Entry save(TenantId tenant, String owner, ModelDefinition definition, int expected, Instant now) {
        CatalogRules.editable(definition, expected);
        Key key = new Key(tenant, owner, definition.ref()); Entry previous = drafts.get(key);
        if (find(tenant, definition.ref()).isPresent() || (previous == null ? 0 : previous.editVersion()) != expected) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
        Entry next = new Entry(definition, definition.digest(), "DRAFT", expected + 1, now); drafts.put(key, next); return next;
    }
    public synchronized Entry publish(TenantId tenant, String owner, ModelDefinition.Ref ref, int expected, String digest, List<ModelDefinition> builtins, Instant now) {
        Entry draft = drafts.get(new Key(tenant, owner, ref));
        if (draft == null) throw new CatalogFailure(CatalogFailure.Code.NOT_FOUND);
        if (draft.editVersion() != expected || !draft.digest().equals(digest)) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
        var existing = find(tenant, ref);
        if (existing.isPresent()) { if (existing.get().digest().equals(digest)) return existing.get(); throw new CatalogFailure(CatalogFailure.Code.CONFLICT); }
        var latest = versions.entrySet().stream().filter(e -> e.getKey().tenant.equals(tenant) && e.getKey().ref.id().equals(ref.id())).map(e -> e.getValue().definition()).max(Comparator.comparingInt(ModelDefinition::revision));
        CatalogRules.publication(draft.definition(), latest, r -> builtins.stream().filter(d -> d.ref().equals(r)).findFirst().or(() -> find(tenant, r).map(Entry::definition)));
        Entry published = new Entry(draft.definition(), digest, "PUBLISHED", 0, now); versions.put(new Key(tenant, "", ref), published); return published;
    }
    public synchronized Optional<Entry> find(TenantId tenant, ModelDefinition.Ref ref) { return Optional.ofNullable(versions.get(new Key(tenant, "", ref))); }
    public synchronized Optional<Entry> draft(TenantId tenant,String owner,ModelDefinition.Ref ref){return Optional.ofNullable(drafts.get(new Key(tenant,owner,ref)));}
    public synchronized Optional<Entry> latest(TenantId tenant,String id){ModelDefinition.checkId(id);return versions.entrySet().stream().filter(e->e.getKey().tenant.equals(tenant)&&e.getKey().ref.id().equals(id)).map(Map.Entry::getValue).max(Comparator.comparingInt(e->e.definition().revision()));}
    public synchronized List<Entry> relations(TenantId tenant,ModelDefinition.Ref endpoint,int limit){if(limit<1||limit>51)throw new IllegalArgumentException();return versions.entrySet().stream().filter(e->e.getKey().tenant.equals(tenant)&&e.getValue().definition().endpoints()!=null&&(e.getValue().definition().endpoints().from().equals(endpoint)||e.getValue().definition().endpoints().to().equals(endpoint))).map(Map.Entry::getValue).sorted(Comparator.comparing((Entry e)->e.definition().id()).thenComparingInt(e->e.definition().revision())).limit(limit).toList();}
    public synchronized List<Entry> published(TenantId tenant, int limit) { return list(versions, tenant, "", limit); }
    public synchronized List<Entry> drafts(TenantId tenant, String owner, int limit) { return list(drafts, tenant, owner, limit); }
    private List<Entry> list(Map<Key, Entry> map, TenantId tenant, String owner, int limit) {
        return map.entrySet().stream().filter(e -> e.getKey().tenant.equals(tenant) && e.getKey().owner.equals(owner)).map(Map.Entry::getValue)
            .sorted(Comparator.comparing((Entry e) -> e.definition().id()).thenComparing(e -> -e.definition().revision())).limit(limit).toList();
    }
}
