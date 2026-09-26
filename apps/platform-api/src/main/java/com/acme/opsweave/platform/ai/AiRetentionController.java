package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/retention")
public final class AiRetentionController {
    private final PrincipalContext principals;private final InventoryWiring wiring;private final ToolExecutor executor;private final FileAiRetentionPolicies policies;
    public AiRetentionController(PrincipalContext principals,InventoryWiring wiring,ToolExecutor executor,@Value("${opsweave.ai.retention-policies-file:}") String file){this.principals=principals;this.wiring=wiring;this.executor=executor;policies=new FileAiRetentionPolicies(file);}
    private static void parameters(HttpServletRequest r){if(!r.getParameterMap().isEmpty())throw new IllegalArgumentException();}
    @GetMapping public Object preview(HttpServletRequest request){parameters(request);var p=principals.requirePrincipal();AiRetention.authorize(p);return executor.run(()->{var policy=policies.require(p.tenantId());var preview=wiring.retention().preview(policy,p.subjectId(),Instant.now().truncatedTo(ChronoUnit.SECONDS));policies.unchanged(policy);return Map.of("schemaVersion","1.0","storage","postgres","policy",AiRetentionJson.policy(policy),"preview",AiRetentionJson.preview(preview));});}
    @PostMapping(value="/runs",consumes="application/json") public Object apply(HttpServletRequest request)throws IOException {
        parameters(request);var p=principals.requirePrincipal();AiRetention.authorize(p);var command=AiRetentionJson.command(ModelSpendJson.read(request));
        return executor.run(()->{var store=wiring.retention();var prior=store.receipt(p.tenantId(),command.requestId());if(prior.isPresent()){var r=prior.get();if(!r.actor().equals(p.subjectId()) || !r.command().equals(command))throw new ToolFailure(ToolFailure.Code.INPUT_CHANGED);return AiRetentionJson.receipt(r);}
            var policy=policies.require(p.tenantId());return AiRetentionJson.receipt(store.apply(policy,p.subjectId(),command,()->policies.unchanged(policy)));});
    }
    @GetMapping("/runs/{requestId}") public Object receipt(@PathVariable String requestId,HttpServletRequest request){parameters(request);var p=principals.requirePrincipal();AiRetention.authorize(p);var id=com.acme.opsweave.platform.incident.IncidentController.uuid(requestId);return executor.run(()->{var r=wiring.retention().receipt(p.tenantId(),id).filter(v->v.actor().equals(p.subjectId())).orElseThrow(()->new ToolFailure(ToolFailure.Code.NOT_FOUND));return AiRetentionJson.receipt(r);});}
}
