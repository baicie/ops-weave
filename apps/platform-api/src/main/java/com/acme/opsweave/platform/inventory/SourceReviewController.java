package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.identity.api.*;
import com.acme.opsweave.integration.domain.CmdbImportPipeline;
import com.acme.opsweave.inventory.application.*;
import com.acme.opsweave.inventory.domain.SourceReview;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.EntityId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/entities/{entityId}/source-reviews")
public final class SourceReviewController {
    private final PrincipalContext principals;
    private final SourceReviewService service;
    private final String storage;
    private final String identityNamespace;
    public SourceReviewController(PrincipalContext principals, AuthorizationService authorization, GetEntityUseCase entities, InventoryWiring wiring,
            @Value("${opsweave.inventory.cmdb-import-source:}") String sourceId, @Value("${opsweave.inventory.identity-namespace:}") String identityNamespace) {
        this.principals = principals; this.storage = wiring.label();
        service = new SourceReviewService(authorization, entities, wiring.sourceReviews(), Clock.systemUTC(), sourceId);
        this.identityNamespace=identityNamespace;
    }
    private static EntityId entity(String value) { return new EntityId(SourceReviewJson.uuid(value)); }
    private static void params(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet()) || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)) throw new IllegalArgumentException("Invalid review parameters");
    }
    @GetMapping public Map<String,Object> page(@PathVariable String entityId, @RequestParam(required=false) String after,
            @RequestParam(defaultValue="25") int limit, HttpServletRequest request) {
        params(request, Set.of("after", "limit")); var id = entity(entityId); var principal = principals.requirePrincipal();
        var result = service.page(principal, id, after == null ? null : SourceReviewJson.uuid(after), limit);
        var items = result.items().stream().limit(limit).toList();
        var body = new LinkedHashMap<String,Object>(); body.put("schemaVersion", "1.0"); body.put("storage", storage); body.put("dataMode", "import");
        body.put("tenantId", principal.tenantId().value()); body.put("entityId", id.value().toString()); body.put("sourceInstanceId", service.sourceId());
        body.put("items", items.stream().map(SourceReviewJson::wire).toList()); body.put("active", result.active() == null ? null : SourceReviewJson.wire(result.active()));
        body.put("after", after); body.put("limit", limit); body.put("nextCursor", result.items().size() > limit ? items.getLast().id().toString() : null);
        body.put("mapping", Map.of("definition", PipelineJson.wire(CmdbImportPipeline.DEFINITION), "digest", CmdbImportPipeline.DIGEST, "engine", CmdbImportPipeline.ENGINE));
        return body;
    }
    @PostMapping public Map<String,Object> stage(@PathVariable String entityId, HttpServletRequest request) throws IOException {
        params(request, Set.of()); var principal = principals.requirePrincipal(); var id = entity(entityId); service.authorize(principal, id);
        var n = PipelineJson.read(request, false); PipelineJson.fields(n, Set.of("requestId", "expectedEntityVersion", "externalId", "observedAt", "values", "mappingDigest", "identity"));
        var identity=AssetIdentityJson.pin(n.get("identity"));
        if(identity!=null && !identity.namespace().equals(identityNamespace)) throw new IllegalArgumentException("Identity namespace is not configured");
        String digest = PipelineJson.text(n, "mappingDigest");
        if (!CmdbImportPipeline.DIGEST.equals(digest)) throw new SourceReview.Conflict("Import mapping version changed");
        var r = service.stage(principal, id, SourceReviewJson.uuid(PipelineJson.text(n,"requestId")), SourceReviewJson.positiveLong(n,"expectedEntityVersion"),
            PipelineJson.text(n,"externalId"), SourceReviewJson.instant(PipelineJson.text(n,"observedAt")), CmdbImportPipeline.map(SourceReviewJson.strings(n.get("values"))), digest, identity);
        return SourceReviewJson.wire(r);
    }
    @PostMapping("/{reviewId}/decisions") public Map<String,Object> decide(@PathVariable String entityId, @PathVariable String reviewId, HttpServletRequest request) throws IOException {
        params(request, Set.of()); var principal = principals.requirePrincipal(); var id = entity(entityId); service.authorize(principal,id);
        var n = PipelineJson.read(request, false); PipelineJson.fields(n, Set.of("requestId", "action", "expectedEntityVersion", "expectedReviewVersion", "choices", "reason"));
        var r = service.decide(principal, id, SourceReviewJson.uuid(reviewId), SourceReviewJson.uuid(PipelineJson.text(n,"requestId")), SourceReview.Action.valueOf(PipelineJson.text(n,"action")),
            SourceReviewJson.positiveLong(n,"expectedEntityVersion"), PipelineJson.integer(n,"expectedReviewVersion", null), SourceReviewJson.choices(n.get("choices")), PipelineJson.text(n,"reason"));
        return SourceReviewJson.wire(r);
    }
}
