package com.acme.opsweave.platform.integration;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.HostPipelineService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/zabbix/hosts/pipeline")
public class HostPipelineController {
    private final PrincipalContext principals;
    private final HostPipelineService pipelines;
    public HostPipelineController(PrincipalContext principals, HostPipelineService pipelines) {
        this.principals = principals; this.pipelines = pipelines;
    }
    @PostMapping(value = "/versions", consumes = "application/json")
    public Object publish(HttpServletRequest request) throws IOException {
        return PipelineJson.wire(pipelines.publish(principals.requirePrincipal(), PipelineJson.definition(PipelineJson.read(request, false))));
    }
    @GetMapping("/versions/{id}/{revision}")
    public Object get(@PathVariable String id, @PathVariable int revision) {
        return PipelineJson.wire(pipelines.get(principals.requirePrincipal(), id, revision));
    }
    @PostMapping(value = "/preview", consumes = "application/json")
    public Object preview(HttpServletRequest request) throws IOException {
        var body = PipelineJson.read(request, false);
        PipelineJson.fields(body, Set.of("syncRunId", "definition", "limit"));
        return pipelines.preview(principals.requirePrincipal(), UUID.fromString(PipelineJson.text(body, "syncRunId")),
            PipelineJson.definition(body.get("definition")), PipelineJson.integer(body, "limit", 100));
    }
    @PostMapping(value = "/replay", consumes = "application/json")
    public Object replay(HttpServletRequest request) throws IOException {
        var body = PipelineJson.read(request, false);
        PipelineJson.fields(body, Set.of("syncRunId", "targetVersion", "limit", "dryRun", "purpose"));
        if (body.has("dryRun") && !body.get("dryRun").isBoolean()) throw new IllegalArgumentException("Invalid dryRun");
        return pipelines.replay(principals.requirePrincipal(), UUID.fromString(PipelineJson.text(body, "syncRunId")),
            PipelineJson.ref(body.get("targetVersion")), PipelineJson.integer(body, "limit", 100),
            !body.has("dryRun") || body.get("dryRun").asBoolean(), PipelineJson.text(body, "purpose"));
    }
}
