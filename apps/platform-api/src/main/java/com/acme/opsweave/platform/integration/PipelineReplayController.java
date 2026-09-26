package com.acme.opsweave.platform.integration;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.api.PipelineReplayStore;
import com.acme.opsweave.integration.application.PipelineReplayService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/zabbix/hosts/pipeline/replay-runs")
public class PipelineReplayController {
    private final PrincipalContext principals;
    private final PipelineReplayService replays;
    private final String storage;
    private final String source;
    public PipelineReplayController(PrincipalContext principals, PipelineReplayService replays,
            InventoryWiring wiring, OpsweaveProperties properties) {
        this.principals = principals; this.replays = replays; storage = wiring.label(); source = properties.zabbix().sourceInstanceId();
    }
    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> execute(HttpServletRequest request) throws IOException {
        var body = PipelineJson.read(request, false);
        PipelineJson.fields(body, Set.of("requestKey", "syncRunId", "targetVersion", "purpose", "limit", "dryRun"));
        if (body.has("dryRun") && (!body.get("dryRun").isBoolean() || !body.get("dryRun").asBoolean())) {
            throw new IllegalArgumentException("Only read-only replay is supported");
        }
        var run = replays.execute(principals.requirePrincipal(), uuid(PipelineJson.text(body, "requestKey")),
            new PipelineReplaySpec(uuid(PipelineJson.text(body, "syncRunId")), PipelineJson.ref(body.get("targetVersion")),
                PipelineJson.integer(body, "limit", 100), PipelineJson.text(body, "purpose")));
        return ResponseEntity.status(run.state() == PipelineReplayRun.State.RUNNING ? 202 : 200).body(detail(run));
    }
    @GetMapping("/{id}")
    public Object get(@PathVariable String id) { return detail(replays.get(principals.requirePrincipal(), uuid(id))); }
    @GetMapping
    public Object list(@RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String before) {
        var page = replays.list(principals.requirePrincipal(), before == null ? null : uuid(before), limit);
        var result = new LinkedHashMap<String, Object>();
        result.put("storage", storage); result.put("items", page.items().stream().map(this::header).toList());
        result.put("nextCursor", page.nextCursor()); return result;
    }
    private Map<String, Object> detail(PipelineReplayRun run) {
        var result = new LinkedHashMap<String, Object>(); result.put("storage", storage);
        result.put("run", header(PipelineReplayStore.Summary.of(run))); result.put("report", run.report()); return result;
    }
    private Map<String, Object> header(PipelineReplayStore.Summary run) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", run.id()); result.put("requestKey", run.requestKey()); result.put("sourceInstanceId", source);
        result.put("spec", run.spec()); result.put("state", run.state()); result.put("attempt", run.attempt());
        result.put("createdAt", run.createdAt().toString()); result.put("updatedAt", run.updatedAt().toString());
        result.put("leaseUntil", run.leaseUntil() == null ? null : run.leaseUntil().toString());
        result.put("failureCode", run.failureCode()); result.put("canResume", run.canResume(Instant.now()));
        result.put("dryRun", true); result.put("writesPerformed", false); return result;
    }
    private static UUID uuid(String value) {
        if (!value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid UUID");
        return UUID.fromString(value);
    }
}
