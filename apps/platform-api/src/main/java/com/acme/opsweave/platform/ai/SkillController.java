package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.application.SkillReleaseService;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

/**
 * Controlled entry points of the platform Skill registry (M6 6a, ADR-105): idempotent draft save,
 * the deterministic check (no model), the review submission, the publish (the approval act of the
 * review gate), the explicit rollback and the read-only readback. The request body never carries
 * identity; reading never advances anything and every command is idempotent on its request id. No
 * endpoint here executes a Skill, calls a model or touches a tool.
 */
@RestController
@RequestMapping("/api/v1/skills")
public final class SkillController {

    private final PrincipalContext principals;
    private final SkillReleaseService service;

    public SkillController(PrincipalContext principals, AuthorizationService authorization, InventoryWiring wiring) {
        this.principals = principals;
        this.service = new SkillReleaseService(authorization, wiring.skillReleases(), Clock.systemUTC());
    }

    /** Idempotent draft save: a replay answers with the same receipt, a stale edit is a conflict. */
    @PostMapping(value = "/drafts", consumes = "application/json")
    public Object save(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        var command = SkillJson.saveCommand(CatalogJson.read(request));
        return SkillJson.wire(service.save(principals.requirePrincipal(), command));
    }

    /**
     * The deterministic check: structural validation of the draft's own evaluation cases against its
     * own schemas. No model is called; the report always says {@code modelComparison: "not-run"}.
     * Reading computes nothing stateful and writes nothing.
     */
    @GetMapping("/{draftId}/check")
    public Object check(@PathVariable String draftId, HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        return SkillJson.wire(service.check(principals.requirePrincipal(), uuid(draftId)));
    }

    /** Idempotent review submission: the content freezes and the draft becomes publishable. */
    @PostMapping(value = "/drafts/{draftId}/reviews", consumes = "application/json")
    public Object submit(@PathVariable String draftId, HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        var command = SkillJson.reviewCommand(CatalogJson.read(request), uuid(draftId));
        return SkillJson.wire(service.submit(principals.requirePrincipal(), command));
    }

    /**
     * Idempotent publish — the approval act of the review gate. Four gates: skill.manage, a passing
     * deterministic check, a publisher who is not the author and a coordinate that is not a reserved
     * packaged Skill.
     */
    @PostMapping(value = "/drafts/{draftId}/publications", consumes = "application/json")
    public Object publish(@PathVariable String draftId, HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        var command = SkillJson.publishCommand(CatalogJson.read(request), uuid(draftId));
        return SkillJson.wire(service.publish(principals.requirePrincipal(), command));
    }

    /** Idempotent rollback: the release pointer moves back, published history never changes. */
    @PostMapping(value = "/{skillId}/rollbacks", consumes = "application/json")
    public Object rollback(@PathVariable String skillId, HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        var command = SkillJson.rollbackCommand(CatalogJson.read(request), skillId);
        return SkillJson.wire(service.rollback(principals.requirePrincipal(), command));
    }

    /** Bounded draft readback, newest first; reading never advances anything. */
    @GetMapping("/drafts")
    public Object drafts(HttpServletRequest request) {
        var bounds = bounds(request);
        int size = bounds.limit() == null ? 20 : bounds.limit();
        var rows = service.drafts(principals.requirePrincipal(), bounds.after(), bounds.limit());
        boolean hasMore = rows.size() > size;
        var items = hasMore ? rows.subList(0, size) : rows;
        return SkillJson.draftPage(items,
            hasMore ? items.get(items.size() - 1).draftId().toString() : null, hasMore);
    }

    /** Read one published version — the immutable pin an AIRun references. */
    @GetMapping("/{skillId}/versions/{version}")
    public Object version(@PathVariable String skillId, @PathVariable String version, HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        return SkillJson.wire(service.version(principals.requirePrincipal(), skillId, version));
    }

    /** Bounded version readback of one Skill; published history is append-only. */
    @GetMapping("/{skillId}/versions")
    public Object versions(@PathVariable String skillId, HttpServletRequest request) {
        var bounds = bounds(request);
        var items = service.versions(principals.requirePrincipal(), skillId, bounds.after(), bounds.limit());
        return SkillJson.versionPage(items, nextVersionCursor(items, bounds.limit()),
            items.size() > (bounds.limit() == null ? 20 : bounds.limit()));
    }

    /** The release pointer of one Skill: what new runs pin right now. */
    @GetMapping("/{skillId}/release")
    public Object release(@PathVariable String skillId, HttpServletRequest request) {
        if (request.getQueryString() != null) throw new IllegalArgumentException();
        return SkillJson.wire(service.release(principals.requirePrincipal(), skillId));
    }

    // ---- helpers ----

    private record Bounds(String after, Integer limit) {}

    private static Bounds bounds(HttpServletRequest request) {
        var params = request.getParameterMap();
        for (var name : params.keySet()) {
            if (!"limit".equals(name) && !"after".equals(name)) throw new IllegalArgumentException();
        }
        Integer limit = null;
        var limits = params.get("limit");
        if (limits != null) {
            if (limits.length != 1) throw new IllegalArgumentException();
            try {
                limit = Integer.valueOf(limits[0]);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException();
            }
        }
        String after = null;
        var afters = params.get("after");
        if (afters != null) {
            if (afters.length != 1) throw new IllegalArgumentException();
            after = afters[0];
        }
        return new Bounds(after, limit);
    }

    private static String nextDraftCursor(java.util.List<com.acme.opsweave.aicontrol.domain.SkillDraft> items, Integer limit) {
        int size = limit == null ? 20 : limit;
        return items.size() > size ? items.get(items.size() - 1).draftId().toString() : null;
    }

    private static String nextVersionCursor(java.util.List<com.acme.opsweave.aicontrol.domain.SkillRelease.Version> items,
            Integer limit) {
        int size = limit == null ? 20 : limit;
        return items.size() > size ? items.get(items.size() - 1).version() : null;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException();
        }
    }
}
