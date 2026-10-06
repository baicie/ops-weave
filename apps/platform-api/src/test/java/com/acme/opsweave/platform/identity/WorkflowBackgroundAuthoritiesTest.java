package com.acme.opsweave.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.workflow.WorkflowRuntimeJson;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class WorkflowBackgroundAuthoritiesTest {
    @TempDir Path directory;
    OidcSettings settings;FileIdentityGrants grants;OidcSessions sessions;WorkflowBackgroundAuthorities background;
    MockHttpServletRequest request;Principal principal;Path policy;
    final AtomicReference<Instant> now=new AtomicReference<>(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
    @BeforeEach void setup()throws Exception {
        settings=new OidcSettings("https://issuer.example.invalid","https://issuer.example.invalid/authorize","https://issuer.example.invalid/token","https://issuer.example.invalid/jwks","fixture","fixture-secret-not-production","https://console.example.invalid",directory.resolve("identity.json").toString(),false,900);
        identity(1,true);grants=new FileIdentityGrants(settings);sessions=new OidcSessions(grants,settings);
        request=new MockHttpServletRequest();sessions.establish(request,settings.issuer(),"fixture-subject",Instant.now().plusSeconds(600));principal=sessions.resolve(request.getSession(false));
        policy=directory.resolve("background.json");subjects(List.of("fixture-subject"));background=new WorkflowBackgroundAuthorities(sessions,grants,policy.toString(),clock);
    }
    void identity(int revision,boolean enabled)throws Exception {
        Files.writeString(Path.of(settings.grantsFile()),CatalogJson.JSON.writeValueAsString(Map.of("schemaVersion","1.0","grants",List.of(Map.of("issuer",settings.issuer(),"externalSubject","fixture-subject","subjectId","fixture-author","tenantId","authority-test","revision",revision,"enabled",enabled,"permissions",List.of("source.sync","entity.read","entity.manage"),"scope",Map.of("tenantWide",true,"resources",List.of()))))));
    }
    void subjects(List<String> values)throws Exception {Files.writeString(policy,CatalogJson.JSON.writeValueAsString(Map.of("schemaVersion","1.0","subjects",values)));}
    WorkflowRuntime.Task task(WorkflowTaskAuthority authority) {return new WorkflowRuntime.Task("fixture-task",1,"sha256:"+"a".repeat(64),new WorkflowRuntime.Settings("source_id","name"),1,"RUNNING",now.get(),new UUID(-1,-1),now.get(),null,authority);}
    void denied(WorkflowFailure.Code code,Runnable action) {assertEquals(code,assertThrows(WorkflowFailure.class,action::run).code());}
    @Test void issueIsExplicitBoundedAndPublicWireDoesNotExposePrivatePolicyReferences() {
        var authority=background.issue(request,principal);assertEquals(20,authority.maxBatches());assertEquals(0,authority.consumedBatches());assertFalse(authority.expiresAt().isAfter(sessions.identity(request.getSession(false)).expiresAt()));
        var task=task(authority);assertEquals(principal,background.resolve(task));assertTrue(background.allowed(request,principal));
        var wire=CatalogJson.JSON.valueToTree(WorkflowRuntimeJson.wire(task));assertTrue(wire.has("authorization"));for(var field:List.of("authority","issuer","externalSubject","grantDigest","permissions","tenantId","token"))assertFalse(wire.has(field));
        var summary=wire.get("authorization");assertEquals(5,summary.size());assertEquals(authority.id().toString(),summary.get("id").asString());
    }
    @Test void identityRevisionDisabledAndMalformedFilesRevokeWithoutOldValueFallback()throws Exception {
        var task=task(background.issue(request,principal));identity(2,true);denied(WorkflowFailure.Code.AUTHORIZATION_REVOKED,()->background.resolve(task));
        identity(1,false);denied(WorkflowFailure.Code.AUTHORIZATION_REVOKED,()->background.resolve(task));
        Files.writeString(Path.of(settings.grantsFile()),"{}");denied(WorkflowFailure.Code.AUTHORIZATION_REVOKED,()->background.resolve(task));assertFalse(background.allowed(request,principal));
    }
    @Test void removingOperatorOptInRevokesExistingWorkButDoesNotRemoveBrowserAuthorization()throws Exception {
        var task=task(background.issue(request,principal));subjects(List.of());denied(WorkflowFailure.Code.AUTHORIZATION_REVOKED,()->background.resolve(task));assertEquals(principal,sessions.resolve(request.getSession(false)));assertFalse(background.allowed(request,principal));
        denied(WorkflowFailure.Code.AUTHORIZATION_REVOKED,()->background.issue(request,principal));
    }
    @Test void expiryIsAbsoluteAndClockRollbackCannotExtendAuthority() {
        var task=task(background.issue(request,principal));now.set(task.authority().expiresAt().minusNanos(1));assertEquals(principal,background.resolve(task));now.set(task.authority().expiresAt());denied(WorkflowFailure.Code.AUTHORIZATION_EXPIRED,()->background.resolve(task));now.set(task.authority().issuedAt().minusNanos(1));denied(WorkflowFailure.Code.AUTHORIZATION_EXPIRED,()->background.resolve(task));
    }
    @Test void restartUsesPersistedReferenceAndCurrentPolicyWithoutRetainingLoginCredentials() {
        var task=task(background.issue(request,principal));var restored=WorkflowRuntimeJson.task(CatalogJson.JSON.writeValueAsString(task));assertEquals(task,restored);
        sessions.clear(request);var restarted=new WorkflowBackgroundAuthorities(new OidcSessions(grants,settings),grants,policy.toString(),clock);assertEquals(principal,restarted.resolve(restored));
        denied(WorkflowFailure.Code.AUTHORIZATION_REVOKED,()->restarted.issue(request,principal));
    }
    @Test void requestCannotSubstituteActorOrIssuer() {
        var other=new Principal(new SubjectId("other"),new TenantId("other"),principal.permissions(),ResourceScope.tenantWide());denied(WorkflowFailure.Code.AUTHORIZATION_REVOKED,()->background.issue(request,other));
        denied(WorkflowFailure.Code.AUTHORIZATION_REVOKED,()->background.issue(new MockHttpServletRequest(),principal));
        var original=background.issue(request,principal);var changed=new WorkflowTaskAuthority(original.id(),"https://different.example.invalid",original.externalSubject(),original.grantDigest(),original.issuedAt(),original.expiresAt(),20,0);
        denied(WorkflowFailure.Code.AUTHORIZATION_REVOKED,()->background.resolve(task(changed)));
    }
    @Test void policyIsClosedAndBoundedAndUnavailableConfigurationDoesNotEnableBackgroundWork()throws Exception {
        var task=task(background.issue(request,principal));
        for(var bad:List.of("{}","{\"schemaVersion\":\"1.0\",\"subjects\":[\"same\",\"same\"]}","{\"schemaVersion\":\"1.0\",\"subjects\":[],\"permissions\":[\"entity.manage\"]}","x".repeat(16385))) {Files.writeString(policy,bad);denied(WorkflowFailure.Code.AUTHORIZATION_REVOKED,()->background.resolve(task));}
        var disabled=new WorkflowBackgroundAuthorities(sessions,grants,"",clock);assertFalse(disabled.configured());assertFalse(disabled.allowed(request,principal));denied(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->disabled.issue(request,principal));
        assertThrows(IllegalStateException.class,()->new WorkflowBackgroundAuthorities(sessions,grants,"relative.json",clock));
    }
    @Test void periodicDiscoverySurvivesAdapterRecreationUsesOriginalGrantAndFailsOnRevisionChange()throws Exception {
        var store=new com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore();
        var model=new com.acme.opsweave.catalog.domain.ModelDefinition("custom.schedule_fixture",1,com.acme.opsweave.catalog.domain.ModelDefinition.Kind.ENTITY,"Fixture","fixture",List.of(new com.acme.opsweave.catalog.domain.ModelDefinition.Field("name","Name",com.acme.opsweave.catalog.domain.ModelDefinition.Type.TEXT,true,255,null,null,List.of())),null);
        var nodes=List.of(new WorkflowDefinition.Node("source",WorkflowDefinition.Type.SOURCE,"1",Map.of()),new WorkflowDefinition.Node("map",WorkflowDefinition.Type.MAP,"1",Map.of("name","name")),new WorkflowDefinition.Node("validate",WorkflowDefinition.Type.VALIDATE,"1",Map.of()),new WorkflowDefinition.Node("output",WorkflowDefinition.Type.OUTPUT,"1",Map.of()));
        var d=WorkflowOperators.builtIn().pin(new WorkflowDefinition("fixture-periodic-host",1,"Fixture periodic host",new WorkflowDefinition.Source("ZABBIX_HOST","host-fixture",new WorkflowDefinition.ConfigurationPin(UUID.randomUUID(),1,"sha256:"+"a".repeat(64))),new WorkflowDefinition.Target(model.id(),1,model.digest()),nodes,List.of(new WorkflowDefinition.Edge("source","map"),new WorkflowDefinition.Edge("map","validate"),new WorkflowDefinition.Edge("validate","output"))));
        var layout=new HashMap<String,com.acme.opsweave.integration.api.WorkflowStore.Position>();for(var node:nodes)layout.put(node.id(),new com.acme.opsweave.integration.api.WorkflowStore.Position(0,0));store.transaction(principal.tenantId(),s->{s.publish(new com.acme.opsweave.integration.api.WorkflowStore.Entry(d,d.digest(),"PUBLISHED",0,layout,now.get(),null),principal.subjectId().value());return null;});
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var runtime=new com.acme.opsweave.integration.application.WorkflowRuntimeService(store,(p,target)->model,(p,s,id)->{throw new AssertionError("No sample read");},(p,s,time,id)->{throw new AssertionError("No legacy source");},new com.acme.opsweave.integration.application.WorkflowRuntimeService.Output(){
            public void validate(Principal p,WorkflowDefinition flow,WorkflowRuntime.Settings config,List<Map<String,Object>> input,WorkflowEvaluation result){throw new AssertionError("Healthy empty source has no asset output");}
            public String write(Principal p,WorkflowDefinition flow,WorkflowRuntime.Settings config,UUID id,Instant observed,Map<String,Object> input,Map<String,Object> output,String origin){throw new AssertionError("No asset write for empty source");}
        },clock,new com.acme.opsweave.integration.application.WorkflowHostRuntimeService.Sources(){public void require(com.acme.opsweave.integration.api.WorkflowStore.Session s,Principal p,WorkflowDefinition.Source source){}public WorkflowHostScan.Page read(Principal p,WorkflowDefinition.Source source,String cursor){reads.incrementAndGet();return new WorkflowHostScan.Page(List.of(),null,true,now.get());}});
        var schedules=new com.acme.opsweave.integration.application.WorkflowHostScheduleService(store,runtime,clock);var command=new WorkflowHostSchedule.Command(UUID.randomUUID(),d.id(),1,d.digest(),new WorkflowRuntime.Settings("entity_id","name"),60,0,WorkflowHostSchedule.Operation.START);
        var authority=background.issue(request,principal);schedules.command(principal,command,()->authority);background.pollHosts(schedules);background.poll(runtime);assertEquals(1,reads.get());assertEquals(1,schedules.status(principal,d.id()).schedule().completedScans());
        sessions.clear(request);var recreated=new WorkflowBackgroundAuthorities(new OidcSessions(grants,settings),grants,policy.toString(),clock);recreated.poll(runtime);now.set(now.get().plusSeconds(60));recreated.pollHosts(schedules);var next=runtime.tasks(principal).getFirst();assertEquals(authority.id(),next.authority().id());assertEquals(1,next.authority().consumedBatches());recreated.poll(runtime);assertEquals(2,reads.get());assertEquals(2,schedules.status(principal,d.id()).schedule().completedScans());
        identity(2,true);recreated.pollHosts(schedules);var stopped=schedules.status(principal,d.id()).schedule();assertEquals("FAILED",stopped.state());assertEquals("AUTHORIZATION_REVOKED",stopped.error());assertEquals(2,stopped.completedScans());assertNotNull(stopped.lastSuccessAt());assertEquals(2,reads.get());
    }
}
