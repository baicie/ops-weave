package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.api.SkillReleaseStore;
import com.acme.opsweave.aicontrol.domain.SkillCheck;
import com.acme.opsweave.aicontrol.domain.SkillContent;
import com.acme.opsweave.aicontrol.domain.SkillDraft;
import com.acme.opsweave.aicontrol.domain.SkillRelease;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Same bounded readback contract for both stores; every input is an explicit synthetic fixture. */
abstract class SkillReleasePaginationContract {
    static final Instant T0 = Instant.parse("2026-10-15T01:00:00Z");
    static final String SKILL = "fixture.pagination";
    static final SubjectId AUTHOR = new SubjectId("fixture-author");
    static final SubjectId REVIEWER = new SubjectId("fixture-reviewer");
    final TenantId tenant = new TenantId("skill-page-" + UUID.randomUUID());

    abstract SkillReleaseStore store();

    @Test
    void draftOrderingUsesCreationTimeAndIdForTiesAcrossEveryPage() {
        var store = store();
        var drafts = new ArrayList<SkillDraft>();
        for (int i = 1; i <= 5; i++) {
            drafts.add(save(store, tenant, SKILL, "1.0." + i, i < 4 ? T0 : T0.plusSeconds(i)));
        }
        drafts.sort(Comparator.comparing(SkillDraft::createdAt).thenComparing(d -> d.draftId().toString()).reversed());
        var first = store.pageDrafts(tenant, null, 3);
        assertEquals(drafts.subList(0, 3), first, "two requested rows plus one sentinel");
        var second = store.pageDrafts(tenant, first.get(1).draftId().toString(), 3);
        assertEquals(drafts.subList(2, 5), second);
        assertEquals(drafts.subList(4, 5), store.pageDrafts(tenant, second.get(1).draftId().toString(), 3));
        assertTrue(store.pageDrafts(tenant, drafts.getLast().draftId().toString(), 3).isEmpty());
        assertEquals(first, store.pageDrafts(tenant, null, 3), "reads preserve the original order");
    }

    @Test
    void versionOrderingUsesPublicationTimeBeforeTheVersionTieBreaker() {
        var store = store();
        publish(store, tenant, SKILL, "1.0.9", T0);
        publish(store, tenant, SKILL, "1.0.2", T0.plusSeconds(1));
        publish(store, tenant, SKILL, "1.0.8", T0.plusSeconds(1));
        publish(store, tenant, SKILL, "1.0.1", T0.plusSeconds(2));
        var before = store.pointer(tenant, SKILL);
        var first = store.pageVersions(tenant, SKILL, null, 3);
        assertEquals(List.of("1.0.1", "1.0.8", "1.0.2"), versions(first));
        var second = store.pageVersions(tenant, SKILL, first.get(1).version(), 3);
        assertEquals(List.of("1.0.2", "1.0.9"), versions(second));
        assertTrue(store.pageVersions(tenant, SKILL, second.getLast().version(), 3).isEmpty());
        assertEquals(before, store.pointer(tenant, SKILL), "paging never moves the release pointer");
    }

    @Test
    void exactLimitHasNoSentinelAndLimitPlusOneIsPreservedForTheHttpAdapter() {
        var store = store();
        publish(store, tenant, SKILL, "1.0.1", T0);
        publish(store, tenant, SKILL, "1.0.2", T0.plusSeconds(1));
        assertEquals(2, store.pageDrafts(tenant, null, 3).size());
        assertEquals(2, store.pageVersions(tenant, SKILL, null, 3).size());
        publish(store, tenant, SKILL, "1.0.3", T0.plusSeconds(2));
        assertEquals(3, store.pageDrafts(tenant, null, 3).size());
        assertEquals(3, store.pageVersions(tenant, SKILL, null, 3).size());
        assertEquals(2, store.pageDrafts(tenant, null, 2).size());
        assertEquals(2, store.pageVersions(tenant, SKILL, null, 2).size());
    }

    @Test
    void absentOrOutOfScopeCursorFailsClosedInsteadOfRestartingOrReturningAnEmptyPage() {
        var store = store();
        publish(store, tenant, SKILL, "1.0.1", T0);
        missing(() -> store.pageDrafts(tenant, UUID.randomUUID().toString(), 3));
        missing(() -> store.pageVersions(tenant, SKILL, "9.0.0", 3));
        var otherTenant = new TenantId("skill-other-" + UUID.randomUUID());
        var foreign = publish(store, otherTenant, SKILL, "9.0.0", T0);
        missing(() -> store.pageDrafts(tenant, foreign.draftId().toString(), 3));
        missing(() -> store.pageVersions(tenant, SKILL, "9.0.0", 3));
        publish(store, tenant, "fixture.other", "8.0.0", T0);
        missing(() -> store.pageVersions(tenant, SKILL, "8.0.0", 3));
        missing(() -> store.pageDrafts(new TenantId("skill-empty-" + UUID.randomUUID()), foreign.draftId().toString(), 3));
    }

    @Test
    void editingADraftDoesNotMoveItsCreationOrderOrInvalidateItsCursor() {
        var store = store();
        var older = save(store, tenant, SKILL, "1.0.1", T0);
        var newer = save(store, tenant, SKILL, "1.0.2", T0.plusSeconds(1));
        store.save(new SkillDraft.SaveCommand(UUID.randomUUID(), SKILL, older.version(), 1, content()),
            tenant, AUTHOR, T0.plusSeconds(2));
        assertEquals(List.of(newer.draftId(), older.draftId()),
            store.pageDrafts(tenant, null, 3).stream().map(SkillDraft::draftId).toList());
        assertEquals(List.of(older.draftId()),
            store.pageDrafts(tenant, newer.draftId().toString(), 3).stream().map(SkillDraft::draftId).toList());
    }

    private static SkillDraft save(SkillReleaseStore store, TenantId tenant, String skillId, String version, Instant at) {
        var saved = store.save(new SkillDraft.SaveCommand(UUID.randomUUID(), skillId, version, 1, content()), tenant, AUTHOR, at);
        return store.findDraft(tenant, saved.draftId()).orElseThrow();
    }

    private static SkillDraft.PublishReceipt publish(SkillReleaseStore store, TenantId tenant, String skillId,
            String version, Instant at) {
        var saved = save(store, tenant, skillId, version, at);
        store.submit(new SkillDraft.ReviewCommand(UUID.randomUUID(), saved.draftId()), tenant, at);
        return store.publish(new SkillDraft.PublishCommand(UUID.randomUUID(), saved.draftId()), tenant, REVIEWER,
            SkillCheck.run(skillId, version, saved.content()), at);
    }

    private static SkillContent content() {
        return new SkillContent("readonly-current-incident-diagnosis-v1", "Fixture read-only diagnosis",
            "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},\"required\":[\"summary\"],\"additionalProperties\":false}",
            List.of("incident.get@2.0.0"), "current", "reasoning-structured-readonly", "forbidden", 1, 1, 1024, 1000,
            List.of(new SkillContent.TestCase("fixture", "Fixture question", "{\"summary\":\"Fixture output\"}")));
    }

    private static List<String> versions(List<SkillRelease.Version> versions) {
        return versions.stream().map(SkillRelease.Version::version).toList();
    }

    private static void missing(Runnable read) {
        assertEquals(ToolFailure.Code.NOT_FOUND, assertThrows(ToolFailure.class, read::run).code());
    }
}
