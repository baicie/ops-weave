package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.aicontrol.application.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.platform.ai.InsightJson;
import com.acme.opsweave.sharedkernel.*;
import com.acme.opsweave.telemetry.application.QueryMetricSeriesUseCase;
import com.acme.opsweave.telemetry.infrastructure.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresAiInsightIT {
    static DriverManagerDataSource source;
    static final Instant NOW=Instant.parse("2026-09-25T02:00:00.123456789Z");
    final TenantId tenant=new TenantId("insight-pg-"+UUID.randomUUID());
    final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    @BeforeAll static void migrate(){
        source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
        for(String version:List.of("V002__host_sync","V003__metric_definition","V004__metric_catalog","V007__pipeline_version","V008__pipeline_replay","V009__pipeline_draft","V010__external_problem_incident","V013__incident_reorganization", "V011__tool_read_evidence","V012__ai_insight"))new SchemaMigrator(source).apply("db/migration/"+version+".sql",version);
        new SchemaMigrator(source).apply("db/migration/V016__problem_observation.sql","V016__problem_observation");
        new SchemaMigrator(source).apply("db/migration/V019__ai_content_retention.sql","V019__ai_content_retention");
    }
    record Fixture(Principal p, PostgresIncidentStore incidents, PostgresToolReadStore tools, PostgresAiInsightStore store, AiInsightService service, InsightSubmission input, AiInsight candidate){}
    Fixture fixture(){
        var p=new Principal(new SubjectId("reader"),tenant,Set.of(Permission.AI_DIAGNOSE,Permission.AI_INSIGHT_READ,Permission.INCIDENT_READ,Permission.ENTITY_READ,Permission.METRIC_READ,Permission.EVIDENCE_READ),ResourceScope.tenantWide());
        var incidents=new PostgresIncidentStore(source);var observation=new ExternalProblem(tenant,"zabbix-1","101","201","Fixture Incident",3,NOW.minusSeconds(30),NOW,List.of(),false,null,null);
        incidents.ingest(tenant,"zabbix-1","labeled-fixture",List.of(observation),NOW);var id=IncidentRecord.initialId(observation);var auth=new AuthorizeUseCase();
        var incidentService=new IncidentService(auth,incidents,clock);var tools=new PostgresToolReadStore(source);var store=new PostgresAiInsightStore(source,clock);var catalog=new InMemoryMetricDefinitionStore();
        var gateway=new ToolGateway(auth,incidentService,new QueryMetricSeriesUseCase(auth,(t,e)->true,catalog,new ClosedMetricQuery(),clock),catalog,tools,e->2000,clock);
        var session=gateway.create(p,id,new ToolWindow(NOW.minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),NOW.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        var first=gateway.incident(p,session.id(),id);var second=gateway.metric(p,session.id(),id,"missing.metric",session.window(),500);
        gateway.recheck(p,session.id(),first.id());gateway.recheck(p,session.id(),second.id());
        var input=new InsightSubmission(UUID.randomUUID(),session.id(),"Summarize",NOW,NOW,NOW,new InsightSubmission.SkillRef("incident.diagnose","2.0.0","sha256:"+"a".repeat(64)),
            new InsightSubmission.ModelRef("mock-deterministic","mock-current-v1"),List.of(first.id(),second.id()),new InsightDraft("Fixture evidence only",List.of(new InsightDraft.Finding("observation","Fixture observation",List.of(first.id()))),List.of(),List.of()));
        var service=new AiInsightService(auth,incidentService,gateway,tools,store,new InsightJson(),clock);
        var candidate=new AiInsight(tenant,p.subjectId(),id,1,Set.of(),session.window(),input,new InsightJson().digest(input),NOW,NOW.plusSeconds(86400),List.of("labeled-fixture","unknown"),List.of("CURRENT_KNOWLEDGE_ONLY"));
        return new Fixture(p,incidents,tools,store,service,input,candidate);
    }
    @Test void concurrentIdenticalSubmissionsSaveOneResultAndReopenExactly() throws Exception {
        var f=fixture();var results=new ArrayList<AiInsight>();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<AiInsight>>();
            for(int i=0;i<6;i++)jobs.add(workers.submit(()->{gate.await();return f.service.submit(f.p,f.input);}));
            gate.countDown();for(var job:jobs)results.add(job.get(20,TimeUnit.SECONDS));
        }
        assertTrue(results.stream().allMatch(results.getFirst()::equals));var reopened=new PostgresAiInsightStore(source,clock).find(tenant,f.input.runId()).orElseThrow();
        assertEquals(results.getFirst(),reopened);assertEquals(NOW,reopened.savedAt());assertEquals(2,count("ai_control.ai_insight_evidence"));assertEquals(1,count("ai_control.ai_insight"));
        assertTrue(new PostgresAiInsightStore(source,clock).find(new TenantId("other"),f.input.runId()).isEmpty());
        var other=new Principal(new SubjectId("other"),tenant,f.p.permissions(),f.p.resourceScope());
        assertEquals(ToolFailure.Code.INPUT_CHANGED,assertThrows(ToolFailure.class,()->f.service.submit(other,f.input)).code());
    }
    @Test void foreignKeyFailureRollsBackTheWholeResult() throws Exception {
        var f=fixture();UUID missing=UUID.randomUUID();
        try(var c=source.getConnection();var update=c.prepareStatement("UPDATE audit.tool_read_call SET evidence_id=? WHERE tenant_id=? AND evidence_id=? AND session_id=?")){
            update.setObject(1,missing);update.setString(2,tenant.value());update.setObject(3,f.input.evidenceIds().get(1));update.setObject(4,f.input.sessionId());update.executeUpdate();
        }
        var input=new InsightSubmission(f.input.runId(),f.input.sessionId(),f.input.question(),NOW,NOW,NOW,f.input.skill(),f.input.model(),List.of(f.input.evidenceIds().getFirst(),missing),f.input.insight());
        var invalid=new AiInsight(tenant,f.p.subjectId(),f.candidate.incidentId(),1,Set.of(),f.candidate.window(),input,new InsightJson().digest(input),NOW,NOW.plusSeconds(86400),f.candidate.dataModes(),f.candidate.warnings());
        assertThrows(RuntimeException.class,()->f.store.save(invalid));assertEquals(0,count("ai_control.ai_insight"));assertEquals(0,count("ai_control.ai_insight_evidence"));
    }
    @Test void finalStoreCheckRejectsChangedIncidentAndExpiredSession() {
        var f=fixture();f.incidents.transition(tenant,f.candidate.incidentId(),new IncidentVisibility(true,Set.of(),true,Set.of()),1,IncidentStatus.INVESTIGATING,UUID.randomUUID(),"reader",NOW);
        assertEquals(ToolFailure.Code.INPUT_CHANGED,assertThrows(ToolFailure.class,()->f.store.save(f.candidate)).code());
        var expired=new PostgresAiInsightStore(source,Clock.fixed(NOW.plusSeconds(61),ZoneOffset.UTC));
        assertEquals(ToolFailure.Code.EXPIRED,assertThrows(ToolFailure.class,()->expired.save(f.candidate)).code());assertTrue(f.store.find(tenant,f.input.runId()).isEmpty());
    }
    long count(String table) throws Exception {
        try(var c=source.getConnection();var read=c.prepareStatement("SELECT count(*) FROM "+table+" WHERE tenant_id=?")){read.setString(1,tenant.value());try(var rows=read.executeQuery()){rows.next();return rows.getLong(1);}}
    }
}
