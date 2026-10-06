import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.SyncRunStore;
import com.acme.opsweave.integration.application.RegisteredSourceScanRunQueryService;
import com.acme.opsweave.integration.domain.SourceConnectionConfiguration;
import com.acme.opsweave.integration.domain.SourceScanRunException;
import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Registered connection scan history is read only, revision scoped and rechecks every stored row. */
public final class RegisteredItemScanRunQuerySmoke {
    private static final TenantId TENANT = new TenantId("registered-scan-tenant");
    private static final TenantId OTHER_TENANT = new TenantId("registered-scan-other");
    private static final UUID SOURCE = UUID.randomUUID();
    private static final SubjectId ACTOR = new SubjectId("registered-scan-reader");
    private static final String PHYSICAL = SourceConnectionConfiguration.physicalId(SOURCE);
    private static int checks;

    public static void main(String[] args) {
        var store = new InMemorySyncRunStore();
        var service = new RegisteredSourceScanRunQueryService(new AuthorizeUseCase(), store);
        var principal = new Principal(ACTOR, TENANT, Set.of(Permission.SOURCE_SYNC),
            ResourceScope.of(Set.of(ResourceRef.source(TENANT, PHYSICAL))));

        fails(SourceScanRunException.Code.FORBIDDEN, () -> service.recent(null, SOURCE, 1, null, 20));
        fails(SourceScanRunException.Code.FORBIDDEN, () -> service.recent(
            new Principal(ACTOR, TENANT, Set.of(Permission.SOURCE_SYNC), ResourceScope.of(Set.of(
                ResourceRef.source(TENANT, SourceConnectionConfiguration.physicalId(UUID.randomUUID()))))),
            UUID.randomUUID(), 1, null, 20));
        fails(SourceScanRunException.Code.FORBIDDEN, () -> new RegisteredSourceScanRunQueryService(
            new AuthorizeUseCase(), store).recent(
                new Principal(ACTOR, TENANT, Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide()),
                SOURCE, 1, null, 20));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.recent(principal, SOURCE, 0, null, 20));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.recent(principal, SOURCE, 101, null, 20));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.recent(principal, SOURCE, 1, "bad-cursor", 20));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.recent(principal, SOURCE, 1, null, 0));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.recent(principal, SOURCE, 1, null,
            RegisteredSourceScanRunQueryService.MAX_LIMIT + 1));
        require(RegisteredSourceScanRunQueryService.DEFAULT_LIMIT == 20
            && RegisteredSourceScanRunQueryService.MAX_LIMIT == SyncRunStore.MAX_RECENT,
            "page size remains bounded by the storage contract");

        var first = store.start(TENANT, PHYSICAL, "item", "zabbix-jsonrpc", scope(SOURCE, 1, "a"));
        var second = store.start(TENANT, PHYSICAL, "item", "zabbix-jsonrpc", scope(SOURCE, 1, "a"));
        var anotherRevision = store.start(TENANT, PHYSICAL, "item", "zabbix-jsonrpc", scope(SOURCE, 2, "b"));
        var anotherSource = UUID.randomUUID();
        store.start(TENANT, SourceConnectionConfiguration.physicalId(anotherSource), "item", "zabbix-jsonrpc", scope(anotherSource, 1, "c"));
        store.start(OTHER_TENANT, PHYSICAL, "item", "zabbix-jsonrpc", scope(SOURCE, 1, "d"));
        store.start(TENANT, PHYSICAL, "item", "fixture", null);

        var page = service.recent(principal, SOURCE, 1, null, 1);
        require(page.items().size() == 1 && page.hasMore() && page.nextCursor() != null,
            "one registered revision page carries an exclusive cursor");
        var next = service.recent(principal, SOURCE, 1, page.nextCursor(), 1);
        require(next.items().size() == 1 && !next.hasMore() && next.nextCursor() == null,
            "registered run cursor reaches the remaining row only");
        require(!page.items().getFirst().run().id().equals(next.items().getFirst().run().id()),
            "cursor pages do not overlap");
        require(Set.of(first.id(), second.id()).equals(Set.of(page.items().getFirst().run().id(), next.items().getFirst().run().id())),
            "only the requested registered connection revision is listed");
        require(service.find(principal, SOURCE, 1, first.id()).run().equals(first),
            "detail returns the original registered item run");
        fails(SourceScanRunException.Code.NOT_FOUND, () -> new RegisteredSourceScanRunQueryService(
            new AuthorizeUseCase(), withFindResult(store, second)).find(principal, SOURCE, 1, first.id()));
        fails(SourceScanRunException.Code.NOT_FOUND, () -> service.find(principal, SOURCE, 1, anotherRevision.id()));
        fails(SourceScanRunException.Code.NOT_FOUND, () -> service.find(principal, SOURCE, 1, UUID.randomUUID()));
        fails(SourceScanRunException.Code.NOT_FOUND, () -> service.find(
            new Principal(ACTOR, OTHER_TENANT, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide()), SOURCE, 1, first.id()));
        fails(SourceScanRunException.Code.INVALID_REQUEST, () -> service.find(principal, SOURCE, 1, null));

        // A store implementation must not widen any trusted source boundary.
        var anotherPhysical = store.start(TENANT, SourceConnectionConfiguration.physicalId(anotherSource), "item",
            "zabbix-jsonrpc", scope(SOURCE, 1, "e"));
        var wrongType = store.start(TENANT, PHYSICAL, "host", "zabbix-jsonrpc", scope(SOURCE, 1, "f"));
        var wrongMode = store.start(TENANT, PHYSICAL, "item", "fixture", scope(SOURCE, 1, "g"));
        var wrongTenant = store.start(OTHER_TENANT, PHYSICAL, "item", "zabbix-jsonrpc", scope(SOURCE, 1, "h"));
        for (var corruptRun : List.of(anotherRevision, anotherPhysical, wrongType, wrongMode, wrongTenant)) {
            var corrupt = new RegisteredSourceScanRunQueryService(new AuthorizeUseCase(), withRegisteredRows(store, corruptRun));
            fails(SourceScanRunException.Code.NOT_FOUND, () -> corrupt.recent(principal, SOURCE, 1, null, 20));
        }
        System.out.println("RegisteredItemScanRunQuerySmoke: " + checks + " checks passed");
    }

    private static SyncRun.SourceScope scope(UUID source, int revision, String seed) {
        return new SyncRun.SourceScope(source, revision, hash(seed), hash("scope-" + seed));
    }

    private static String hash(String seed) {
        int value = seed.hashCode();
        var hex = new StringBuilder("sha256:");
        for (int i = 0; i < 64; i++) hex.append(Character.forDigit((value >>> ((i % 8) * 4)) & 0xf, 16));
        return hex.toString();
    }

    private static SyncRunStore withRegisteredRows(SyncRunStore delegate, SyncRun row) {
        return (SyncRunStore) Proxy.newProxyInstance(SyncRunStore.class.getClassLoader(), new Class<?>[]{SyncRunStore.class},
            (proxy, method, args) -> {
                if (method.getName().equals("registeredRecent")) return List.of(row);
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            });
    }

    private static SyncRunStore withFindResult(SyncRunStore delegate, SyncRun row) {
        return (SyncRunStore) Proxy.newProxyInstance(SyncRunStore.class.getClassLoader(), new Class<?>[]{SyncRunStore.class},
            (proxy, method, args) -> {
                if (method.getName().equals("find")) return Optional.of(row);
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
        checks++;
    }

    private static void fails(SourceScanRunException.Code code, Runnable action) {
        try {
            action.run();
        } catch (SourceScanRunException expected) {
            require(expected.code() == code, "expected " + code + " but got " + expected.code());
            return;
        }
        throw new IllegalStateException("Expected " + code);
    }
}
