package com.acme.opsweave.catalog.infrastructure;

import com.acme.opsweave.catalog.api.ModelExtensionStore;
import com.acme.opsweave.catalog.domain.CatalogFailure;
import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.catalog.domain.ModelExtension;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

/** Explicit ephemeral store only when inventory.store=memory; never a PostgreSQL fallback. */
public final class InMemoryModelExtensionStore implements ModelExtensionStore {
    private record DraftKey(TenantId tenant, String owner, ModelDefinition.Ref base) {}
    private record VersionKey(TenantId tenant, ModelDefinition.Ref base, int revision) {}

    private final Map<DraftKey, Entry> drafts = new HashMap<>();
    private final Map<VersionKey, Entry> versions = new HashMap<>();

    @Override
    public synchronized Entry save(TenantId tenant, String owner, ModelDefinition.Ref base, List<ModelDefinition.Field> fields, int expected, Instant now) {
        var shape = new ModelExtension(base, 1, fields);
        DraftKey key = new DraftKey(tenant, owner, base);
        Entry previous = drafts.get(key);
        if ((previous == null ? 0 : previous.editVersion()) != expected) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
        int revision = latestRevision(tenant, base) + 1;
        var extension = revision == 1 ? shape : new ModelExtension(base, revision, fields);
        Entry next = new Entry(extension, "DRAFT", expected + 1, now);
        drafts.put(key, next);
        return next;
    }

    @Override
    public synchronized Entry publish(TenantId tenant, String owner, ModelDefinition.Ref base, int expected, String digest, Instant now) {
        Entry draft = drafts.get(new DraftKey(tenant, owner, base));
        if (draft == null) throw new CatalogFailure(CatalogFailure.Code.NOT_FOUND);
        if (draft.editVersion() != expected || !draft.extension().digest().equals(digest)) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
        var existing = versions.get(new VersionKey(tenant, base, draft.extension().revision()));
        if (existing != null) {
            if (existing.extension().digest().equals(digest)) return existing;
            throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
        }
        int latest = latestRevision(tenant, base);
        if (draft.extension().revision() != latest + 1) throw new CatalogFailure(CatalogFailure.Code.CONFLICT);
        if (latest == 0) {
            if (draft.extension().revision() != 1) throw new CatalogFailure(CatalogFailure.Code.INCOMPATIBLE_REVISION);
        } else {
            draft.extension().requireCompatibleWith(versions.get(new VersionKey(tenant, base, latest)).extension());
        }
        Entry published = new Entry(draft.extension(), "PUBLISHED", 0, now);
        versions.put(new VersionKey(tenant, base, draft.extension().revision()), published);
        return published;
    }

    @Override
    public synchronized Optional<Entry> find(TenantId tenant, ModelDefinition.Ref base, int revision) {
        return Optional.ofNullable(versions.get(new VersionKey(tenant, base, revision)));
    }

    @Override
    public synchronized Optional<Entry> latest(TenantId tenant, ModelDefinition.Ref base) {
        int revision = latestRevision(tenant, base);
        return revision == 0 ? Optional.empty() : Optional.of(versions.get(new VersionKey(tenant, base, revision)));
    }

    @Override
    public synchronized List<Entry> published(TenantId tenant, int limit) {
        if (limit < 1 || limit > 51) throw new IllegalArgumentException();
        return versions.entrySet().stream().filter(row -> row.getKey().tenant().equals(tenant)).map(Map.Entry::getValue)
            .sorted(Comparator.comparing((Entry entry) -> entry.extension().base().id())
                .thenComparing(entry -> entry.extension().base().revision())
                .thenComparingInt(entry -> -entry.extension().revision())).limit(limit).toList();
    }

    @Override
    public synchronized List<Entry> drafts(TenantId tenant, String owner, int limit) {
        if (limit < 1 || limit > 51) throw new IllegalArgumentException();
        return drafts.entrySet().stream().filter(row -> row.getKey().tenant().equals(tenant) && row.getKey().owner().equals(owner))
            .map(Map.Entry::getValue)
            .sorted(Comparator.comparing((Entry entry) -> entry.extension().base().id())
                .thenComparingInt(entry -> entry.extension().base().revision())).limit(limit).toList();
    }

    private int latestRevision(TenantId tenant, ModelDefinition.Ref base) {
        return versions.keySet().stream().filter(key -> key.tenant().equals(tenant) && key.base().equals(base))
            .mapToInt(VersionKey::revision).max().orElse(0);
    }
}
