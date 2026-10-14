package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.api.AiRunDispatch;
import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The production diagnosis seam: dispatch the claimed run's fixed content to the Agent Runtime's
 * synchronous current-knowledge diagnosis call (the unchanged M4 chain: one model call on the shared
 * budget, evidence recheck and one idempotent AIInsight save). The body is the closed request shape
 * of that call — no identity, no permission, no execution hint crosses the boundary, and the pinned
 * Skill is the one recorded on the run at submission.
 */
final class RuntimeAiRunDispatch implements AiRunDispatch {
    private final RuntimeDispatcher runtime;
    private final String authorization;

    RuntimeAiRunDispatch(RuntimeDispatcher runtime, String token) {
        this.runtime = runtime;
        this.authorization = "Bearer " + token;
    }

    @Override
    public void dispatch(AiRun.Run run, Principal as) {
        if (!run.tenantId().equals(as.tenantId()) || !run.subjectId().equals(as.subjectId())) {
            throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("runId", run.runId().toString());
        body.put("incidentId", run.incidentId().toString());
        body.put("question", run.question());
        body.put("timeRange", Map.of("from", run.window().from().toString(), "to", run.window().to().toString()));
        body.put("knowledgeMode", run.knowledgeMode());
        runtime.dispatch(authorization, CatalogJson.JSON.writeValueAsString(body));
    }
}
