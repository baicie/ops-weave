package com.acme.opsweave.platform.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.ZabbixHistoryPort;
import com.acme.opsweave.integration.application.ReadZabbixHistoryUseCase;
import com.acme.opsweave.integration.domain.HistoryPage;
import com.acme.opsweave.integration.domain.HistoryWindow;
import com.acme.opsweave.integration.domain.ZabbixItemMapper;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricDefinitionStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HistoryAuthorizationTest {
    private static final TenantId TENANT = new TenantId("tenant-demo");
    private static final Set<Permission> ALL = Set.of(Permission.SOURCE_SYNC, Permission.ENTITY_READ, Permission.METRIC_READ);
    private static final HistoryWindow WINDOW = new HistoryWindow(10, 20, null, 10);
    private final InMemoryMetricDefinitionStore store = new InMemoryMetricDefinitionStore();

    HistoryAuthorizationTest() {
        var mappings = ClasspathMappingCatalog.load(getClass().getClassLoader());
        var mapped = new ZabbixItemMapper(mappings).map(TENANT, "zabbix-1", Map.of(
            "itemid", "20001", "hostid", "10084", "key_", "system.cpu.util[,user]", "name", "CPU user", "units", "%", "value_type", "0"));
        store.upsert(mapped.definition());
        store.upsert(mapped.binding());
    }

    @Test
    void deniesMissingPermissionsTenantAndObjectScopeBeforeAnySourceCall() {
        AtomicInteger calls = new AtomicInteger();
        var useCase = useCase((source, binding, window) -> { calls.incrementAndGet(); return HistoryPage.select(window, List.of()); });
        for (Permission missing : ALL) {
            var permissions = new java.util.HashSet<>(ALL);
            permissions.remove(missing);
            assertEquals("FORBIDDEN", useCase.execute(principal(TENANT, permissions, ResourceScope.tenantWide()), "20001", WINDOW).error());
        }
        assertEquals("BINDING_UNAVAILABLE", useCase.execute(principal(new TenantId("other-tenant"), ALL, ResourceScope.tenantWide()), "20001", WINDOW).error());
        var source = ResourceRef.source(TENANT, "zabbix-1");
        var metric = ResourceRef.metric(TENANT, "host.cpu.usage.user");
        var entity = ResourceRef.entity(TENANT, store.findBinding(TENANT, "zabbix-1", "20001").orElseThrow().entityId());
        assertEquals("BINDING_UNAVAILABLE", useCase.execute(principal(TENANT, ALL, ResourceScope.of(Set.of(source, metric))), "20001", WINDOW).error());
        assertEquals("BINDING_UNAVAILABLE", useCase.execute(principal(TENANT, ALL, ResourceScope.of(Set.of(source, entity))), "20001", WINDOW).error());
        assertEquals("FORBIDDEN", useCase.execute(principal(TENANT, ALL, ResourceScope.of(Set.of(metric, entity))), "20001", WINDOW).error());
        assertEquals(0, calls.get());
        assertEquals(ReadZabbixHistoryUseCase.Kind.SUCCESS,
            useCase.execute(principal(TENANT, ALL, ResourceScope.of(Set.of(source, metric, entity))), "20001", WINDOW).kind());
        assertEquals(1, calls.get());
        store.retireMissing(TENANT, "zabbix-1", Set.of());
        assertEquals("BINDING_UNAVAILABLE", useCase.execute(principal(TENANT, ALL, ResourceScope.tenantWide()), "20001", WINDOW).error());
        assertEquals(1, calls.get());
    }

    @Test
    void sourceFailureReturnsNoPointsOrCursorAndDoesNotRetireBindings() {
        var useCase = useCase((source, binding, window) -> { throw new IllegalStateException("private-response-token"); });
        var result = useCase.execute(principal(TENANT, ALL, ResourceScope.tenantWide()), "20001", WINDOW);
        assertEquals("SOURCE_FETCH_FAILED", result.error());
        assertNull(result.page());
        assertNull(result.binding());
        assertFalse(result.toString().contains("private-response-token"));
        assertEquals("ACTIVE", store.findBinding(TENANT, "zabbix-1", "20001").orElseThrow().lifecycle().name());
    }

    @Test
    void capsInFlightReadsAndReleasesBudgetAfterCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        var useCase = useCase((source, binding, window) -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            return HistoryPage.select(window, List.of());
        });
        Principal principal = principal(TENANT, ALL, ResourceScope.tenantWide());
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<ReadZabbixHistoryUseCase.Result>> tasks = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) tasks.add(executor.submit(() -> useCase.execute(principal, "20001", WINDOW)));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals("HISTORY_BUSY", useCase.execute(principal, "20001", WINDOW).error());
            } finally { release.countDown(); }
            for (var task : tasks) assertEquals(ReadZabbixHistoryUseCase.Kind.SUCCESS, task.get(3, TimeUnit.SECONDS).kind());
        }
        assertEquals(ReadZabbixHistoryUseCase.Kind.SUCCESS, useCase.execute(principal, "20001", WINDOW).kind());
    }

    private ReadZabbixHistoryUseCase useCase(ZabbixHistoryPort port) {
        return new ReadZabbixHistoryUseCase(new AuthorizeUseCase(), store, port, "zabbix-1", "stub-ref", "labeled-fixture",
            Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
    }
    private static Principal principal(TenantId tenant, Set<Permission> permissions, ResourceScope scope) {
        return new Principal(new SubjectId("tester"), tenant, permissions, scope);
    }
}
