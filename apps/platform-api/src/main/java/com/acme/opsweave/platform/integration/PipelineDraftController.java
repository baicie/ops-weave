package com.acme.opsweave.platform.integration;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.PipelineDraftService;
import com.acme.opsweave.integration.domain.PipelineDraft;
import com.acme.opsweave.integration.domain.PipelineVersion;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations/zabbix/hosts/pipeline/drafts")
public class PipelineDraftController {
    private final PrincipalContext principals;
    private final PipelineDraftService drafts;
    private final String storage;
    public PipelineDraftController(PrincipalContext principals, PipelineDraftService drafts, InventoryWiring wiring) {
        this.principals = principals; this.drafts = drafts; storage = wiring.label();
    }
    @PostMapping(consumes = "application/json")
    public Object save(HttpServletRequest request) throws IOException {
        var body = PipelineJson.read(request, false);
        PipelineJson.fields(body, Set.of("definition", "expectedEditVersion"));
        return detail(drafts.save(principals.requirePrincipal(), PipelineJson.definition(body.get("definition")),
            PipelineJson.integer(body, "expectedEditVersion", null)));
    }
    @GetMapping("/{id}/{revision}")
    public Object get(@PathVariable String id, @PathVariable int revision) { return detail(drafts.get(principals.requirePrincipal(), id, revision)); }
    @GetMapping
    public Object list(@RequestParam(defaultValue = "20") int limit) {
        var recent = drafts.list(principals.requirePrincipal(), limit);
        return Map.of("storage", storage, "items", recent.items().stream().map(item -> Map.of("target", item.target(),
            "editVersion", item.editVersion(), "updatedAt", item.updatedAt().toString())).toList(), "truncated", recent.truncated());
    }
    private Object detail(PipelineDraft draft) {
        return Map.of("storage", storage, "definition", PipelineJson.wire(draft.content().definition()), "digest", draft.content().digest(),
            "engine", PipelineVersion.ENGINE, "state", "DRAFT", "editVersion", draft.editVersion(), "updatedAt", draft.updatedAt().toString());
    }
}
