package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.api.SkillReleaseStore;
import com.acme.opsweave.aicontrol.infrastructure.InMemorySkillReleaseStore;

class SkillReleasePaginationTest extends SkillReleasePaginationContract {
    private final SkillReleaseStore store = new InMemorySkillReleaseStore();
    @Override SkillReleaseStore store() { return store; }
}
