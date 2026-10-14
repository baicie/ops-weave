package com.acme.opsweave.aicontrol.api;

import com.acme.opsweave.aicontrol.domain.AiRun;
import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.identity.domain.Principal;

/**
 * The executor's diagnosis seam (M5 5b): run the pinned current-knowledge diagnosis chain for one
 * claimed run. The production implementation dispatches the run's fixed content to the Agent
 * Runtime's synchronous diagnosis call (one model call, shared model budget, evidence recheck and an
 * idempotent AIInsight save — unchanged M4 semantics); the executor only decides <em>when</em> the
 * chain may run and where the run lands afterwards.
 *
 * <p>A dispatch that cannot complete throws a {@link ToolFailure}; the chain itself is fail-stop
 * before the save, so a failed dispatch leaves no AIInsight behind and a partial result never
 * pretends to be a complete diagnosis. Nothing here may add a tool, a permission or a second model
 * call for the same run.
 */
public interface AiRunDispatch {
    /** Execute the diagnosis chain as the given (trusted) principal for this run. */
    void dispatch(AiRun.Run run, Principal as);
}
