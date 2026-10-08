package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.acme.opsweave.integration.domain.SyncRun;
import com.acme.opsweave.integration.infrastructure.InMemorySyncRunStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisteredSyncRunStoreTest {
    @Test
    void storesRegisteredScopeAndRetiredCountWhileLegacyRunsRemainUnscoped() {
        var store = new InMemorySyncRunStore();
        var tenant = new TenantId("tenant-registered-runs");
        var sourceId = UUID.randomUUID();
        var scope = new SyncRun.SourceScope(sourceId, 3, digest('a'), digest('b'), "zabbix-registered");

        var registered = store.start(tenant, "zabbix-registered", "item", "zabbix-jsonrpc", scope);
        store.succeed(tenant, registered.id(), "itemid-watermark-snapshot", 7);

        var restored = store.find(tenant, registered.id()).orElseThrow();
        assertEquals(scope, restored.sourceScope());
        assertEquals(7, restored.retired());
        assertEquals(1, store.registeredRecent(tenant, sourceId, 3, null, 20).size());
        assertEquals(0, store.registeredRecent(tenant, sourceId, 2, null, 20).size());

        var legacy = store.start(tenant, "zabbix-legacy", "item", "labeled-fixture");
        store.succeed(tenant, legacy.id(), "itemid-watermark-snapshot");
        var legacyRestored = store.find(tenant, legacy.id()).orElseThrow();
        assertNull(legacyRestored.sourceScope());
        assertEquals(0, legacyRestored.retired());

        var incompleteScope = new SyncRun.SourceScope(sourceId, 3, digest('a'), digest('b'), "zabbix-incomplete");
        var incomplete = store.start(tenant, "zabbix-incomplete", "item", "zabbix-jsonrpc", incompleteScope);
        assertThrows(IllegalArgumentException.class,
            () -> store.succeed(tenant, incomplete.id(), "offset-scan-attempt", 1));
        assertThrows(IllegalArgumentException.class,
            () -> store.succeed(tenant, incomplete.id(), "itemid-watermark-snapshot", -1));
        assertEquals(com.acme.opsweave.integration.domain.SyncStatus.RUNNING,
            store.find(tenant, incomplete.id()).orElseThrow().status());
    }

    private static String digest(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }
}
