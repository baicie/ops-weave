package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.api.SkillReleaseStore;
import com.acme.opsweave.aicontrol.application.SkillReleaseService;
import com.acme.opsweave.aicontrol.domain.SkillContent;
import com.acme.opsweave.aicontrol.domain.SkillDraft;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.domain.Authorizer;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fresh scope checks must precede any content read when the object is named in the request. */
class SkillReadAuthorizationTest {
    private static final TenantId TENANT = new TenantId("skill-read-fixture");
    private static final String SKILL = "fixture.diagnosis";

    @Test
    void excludedSkillAndTenantWideEnumerationNeverReachTheStore() {
        var store = mock(SkillReleaseStore.class);
        var service = new SkillReleaseService(new Authorizer()::decide, store, Clock.systemUTC());
        var principal = scoped("fixture.other");
        forbidden(() -> service.version(principal, SKILL, "1.0.0"));
        forbidden(() -> service.versions(principal, SKILL, null, 20));
        forbidden(() -> service.release(principal, SKILL));
        forbidden(() -> service.drafts(principal, null, 20));
        verifyNoInteractions(store);
    }

    @Test
    void draftIdLookupUsesOnlyTheTrustedTenantThenChecksTheActualSkill() {
        var store = mock(SkillReleaseStore.class);
        var service = new SkillReleaseService(new Authorizer()::decide, store, Clock.systemUTC());
        var content = new SkillContent("readonly-current-incident-diagnosis-v1", "Fixture prompt", "{}",
            List.of("incident.get@2.0.0"), "current", "reasoning-structured-readonly", "forbidden",
            1, 1, 1024, 1000, List.of(new SkillContent.TestCase("fixture", "Fixture question", "{}")));
        var draft = SkillDraft.create(TENANT, UUID.randomUUID(), SKILL, "1.0.0", content,
            new SubjectId("author"), Instant.parse("2026-10-15T00:00:00Z"));
        when(store.findDraft(TENANT, draft.draftId())).thenReturn(Optional.of(draft));
        forbidden(() -> service.draft(scoped("fixture.other"), draft.draftId()));
        forbidden(() -> service.check(scoped("fixture.other"), draft.draftId()));
        assertEquals(draft, service.draft(scoped(SKILL), draft.draftId()));
        var other = new Principal(new SubjectId("reader"), new TenantId("other-fixture"),
            Set.of(Permission.SKILL_READ), ResourceScope.tenantWide());
        var missing = assertThrows(ToolFailure.class, () -> service.draft(other, draft.draftId()));
        assertEquals(ToolFailure.Code.NOT_FOUND, missing.code());
        verify(store).findDraft(other.tenantId(), draft.draftId());
    }

    private static Principal scoped(String skillId) {
        return new Principal(new SubjectId("reader"), TENANT, Set.of(Permission.SKILL_READ),
            ResourceScope.of(Set.of(new ResourceRef(TENANT, "skill", skillId))));
    }

    private static void forbidden(Runnable operation) {
        assertEquals(ToolFailure.Code.FORBIDDEN, assertThrows(ToolFailure.class, operation::run).code());
    }
}
