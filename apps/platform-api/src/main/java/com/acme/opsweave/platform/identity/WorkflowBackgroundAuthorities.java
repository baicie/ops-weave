package com.acme.opsweave.platform.identity;

import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.application.WorkflowRuntimeService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Explicit operator opt-in and bounded delegation; the cache contains routing metadata, never privileges. */
@Component
@ConditionalOnProperty(name="opsweave.auth.mode",havingValue="oidc")
public final class WorkflowBackgroundAuthorities {
    private record Context(TenantId tenant,String owner) {}
    private final OidcSessions sessions;
    private final FileIdentityGrants grants;
    private final Path policy;
    private final Clock clock;
    private final com.acme.opsweave.integration.application.WorkflowOwnerRoundRobin<Context> contexts=new com.acme.opsweave.integration.application.WorkflowOwnerRoundRobin<>(40);
    private final com.acme.opsweave.integration.application.WorkflowOwnerRoundRobin<Context> metricContexts=new com.acme.opsweave.integration.application.WorkflowOwnerRoundRobin<>(40);
    private final com.acme.opsweave.integration.application.WorkflowOwnerRoundRobin<Context> logContexts=new com.acme.opsweave.integration.application.WorkflowOwnerRoundRobin<>(40);
    private final com.acme.opsweave.integration.application.WorkflowOwnerRoundRobin<Context> hostContexts=new com.acme.opsweave.integration.application.WorkflowOwnerRoundRobin<>(40);

    @Autowired
    public WorkflowBackgroundAuthorities(OidcSessions sessions,FileIdentityGrants grants,
        @Value("${opsweave.workflow.runtime-grants-file:}") String file) {
        this(sessions,grants,file,Clock.systemUTC());
    }
    public WorkflowBackgroundAuthorities(OidcSessions sessions,FileIdentityGrants grants,String file,Clock clock) {
        this.sessions=sessions; this.grants=grants; this.clock=clock;
        policy=file.isBlank()?null:Path.of(file);
        if(policy!=null) { if(!policy.isAbsolute()) throw new IllegalStateException("Background authorization configuration unavailable"); subjects(); }
    }
    public boolean configured() { return policy!=null; }
    public boolean allowed(HttpServletRequest request,Principal expected) {
        try { var session=request.getSession(false); var identity=sessions.identity(session);
            return policy!=null && identity!=null && sessions.resolve(session).equals(expected)
                && expected.has(com.acme.opsweave.identity.domain.Permission.ENTITY_MANAGE) && subjects().contains(identity.subject());
        } catch(RuntimeException unavailable) { return false; }
    }

    public boolean allowedMetric(HttpServletRequest request,Principal expected){try{var session=request.getSession(false);var identity=sessions.identity(session);return policy!=null&&identity!=null&&sessions.resolve(session).equals(expected)&&subjects().contains(identity.subject());}catch(RuntimeException unavailable){return false;}}
    public WorkflowTaskAuthority issue(HttpServletRequest request,Principal expected) {
        if(policy==null) throw fail(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        try {
            var session=request.getSession(false); var identity=sessions.identity(session);
            if(identity==null || !sessions.resolve(session).equals(expected) || !subjects().contains(identity.subject()))
                throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
            var bound=grants.find(identity.subject()); var now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var deadline=now.plusSeconds(900); if(identity.expiresAt().isBefore(deadline)) deadline=identity.expiresAt();
            if(!bound.grant().principal().equals(expected) || !now.isBefore(deadline)) throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
            remember(new Context(expected.tenantId(),expected.subjectId().value()));rememberMetric(new Context(expected.tenantId(),expected.subjectId().value()));rememberLog(new Context(expected.tenantId(),expected.subjectId().value()));rememberHost(new Context(expected.tenantId(),expected.subjectId().value()));
            return new WorkflowTaskAuthority(UUID.randomUUID(),identity.issuer(),identity.subject(),"sha256:"+bound.digest(),now,deadline,20,0);
        } catch(WorkflowFailure known) { throw known; }
        catch(RuntimeException invalid) { throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED); }
    }
    public Principal resolve(WorkflowRuntime.Task task) {
        return resolve(task.authority());
    }
    public Principal resolve(WorkflowTaskAuthority authority){
        if(authority==null) throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
        var now=clock.instant();
        if(now.isBefore(authority.issuedAt()) || !now.isBefore(authority.expiresAt())) throw fail(WorkflowFailure.Code.AUTHORIZATION_EXPIRED);
        try {
            if(!subjects().contains(authority.externalSubject())) throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
            var bound=grants.find(authority.externalSubject());
            if(!authority.grantDigest().equals("sha256:"+bound.digest())) throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED);
            return bound.grant().bind(authority.issuer(),authority.externalSubject(),authority.expiresAt(),now);
        } catch(WorkflowFailure known) { throw known; }
        catch(RuntimeException invalid) { throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED); }
    }
    /** Current file maps determine discovery. Removed owners remain only long enough to fail closed. */
    public void poll(WorkflowRuntimeService runtime) {
        try {
            for(var subject:subjects()) {
                var p=grants.inspect(subject).grant().principal();
                remember(new Context(p.tenantId(),p.subjectId().value()));
            }
        } catch(RuntimeException unavailable) { /* Existing metadata can be denied without caching old authorization. */ }
        var context=contexts.next();
        if(context==null)return;
        runtime.tickAuthorized(context.tenant(),context.owner(),this::resolve);
        if(!runtime.hasAuthorizedTasks(context.tenant(),context.owner())) contexts.remove(context);
    }
    private void rememberLog(Context context){logContexts.remember(context);}
    private void rememberMetric(Context context){metricContexts.remember(context);}
    private void rememberHost(Context context){hostContexts.remember(context);}
    public void pollHosts(com.acme.opsweave.integration.application.WorkflowHostScheduleService runtime){
        try{for(var subject:subjects()){var p=grants.inspect(subject).grant().principal();rememberHost(new Context(p.tenantId(),p.subjectId().value()));}}catch(RuntimeException unavailable){}
        var context=hostContexts.next();if(context==null)return;runtime.tickAuthorized(context.tenant(),context.owner(),this::resolve);if(!runtime.hasAuthorizedTasks(context.tenant(),context.owner()))hostContexts.remove(context);
    }
    public void pollLogs(com.acme.opsweave.integration.application.WorkflowLogStreamService runtime){
        try{for(var subject:subjects()){var p=grants.inspect(subject).grant().principal();rememberLog(new Context(p.tenantId(),p.subjectId().value()));}}catch(RuntimeException unavailable){}
        var context=logContexts.next();if(context==null)return;runtime.tickAuthorized(context.tenant(),context.owner(),task->resolve(task.authority()));if(!runtime.hasAuthorizedTasks(context.tenant(),context.owner()))logContexts.remove(context);
    }
    public void pollMetrics(com.acme.opsweave.integration.application.WorkflowMetricStreamService runtime){
        try{for(var subject:subjects()){var p=grants.inspect(subject).grant().principal();rememberMetric(new Context(p.tenantId(),p.subjectId().value()));}}catch(RuntimeException unavailable){}
        var context=metricContexts.next();if(context==null)return;runtime.tickAuthorized(context.tenant(),context.owner(),task->resolve(task.authority()));if(!runtime.hasAuthorizedTasks(context.tenant(),context.owner()))metricContexts.remove(context);
    }
    private void remember(Context context) {
        contexts.remember(context);
    }
    private Set<String> subjects() {
        if(policy==null) return Set.of();
        try {
            if(!Files.isRegularFile(policy,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
            byte[] bytes; try(var input=Files.newInputStream(policy)) { bytes=input.readNBytes(16385); }
            if(bytes.length==0 || bytes.length>16384) throw new IllegalArgumentException();
            var root=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes);
            FileIdentityGrants.exact(root,"schemaVersion","subjects");
            if(!FileIdentityGrants.text(root,"schemaVersion").equals("1.0") || !root.get("subjects").isArray() || root.get("subjects").size()>20) throw new IllegalArgumentException();
            var result=new LinkedHashSet<String>();
            for(var node:root.get("subjects")) {
                if(!node.isString() || node.asString().isBlank() || node.asString().length()>255
                    || node.asString().codePoints().anyMatch(Character::isISOControl) || !result.add(node.asString())) throw new IllegalArgumentException();
            }
            return Set.copyOf(result);
        } catch(Exception invalid) { throw fail(WorkflowFailure.Code.AUTHORIZATION_REVOKED); }
    }
    private static WorkflowFailure fail(WorkflowFailure.Code code) { return new WorkflowFailure(code); }
}
