package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.platform.ai.AiRetentionJson;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresAiRetentionIT {
    @BeforeAll static void migrate(){PostgresAiInsightIT.migrate();new SchemaMigrator(PostgresAiInsightIT.source).apply("db/migration/V018__model_spend.sql","V018__model_spend");}
    final PostgresAiInsightIT fixture=new PostgresAiInsightIT();
    final PostgresAiInsightIT.Fixture f=fixture.fixture();
    final Instant now=PostgresAiInsightIT.NOW.plusSeconds(3*86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    final PostgresAiRetentionStore store=new PostgresAiRetentionStore(PostgresAiInsightIT.source,Clock.fixed(now,ZoneOffset.UTC));
    AiRetention.Policy policy(int batch,Set<UUID> holds,boolean enabled){return new AiRetention.Policy(fixture.tenant,"fixture-v1",1,1,1,batch,holds,enabled);}
    AiRetention.Command command(AiRetention.Preview p){return new AiRetention.Command(UUID.randomUUID(),p.policyDigest(),p.asOf(),p.digest());}
    void save(){f.service().submit(f.p(),f.input());}
    long bodies(String table)throws Exception{try(var c=PostgresAiInsightIT.source.getConnection();var s=c.prepareStatement("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND snapshot IS NOT NULL")){s.setString(1,fixture.tenant.value());try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    @Test void purgesBodiesAndAuditAtomicallyButRetainsIdsScopesAndReferences()throws Exception{
        save();var ledger=new PostgresModelSpendStore(PostgresAiInsightIT.source);var cost=new ModelSpend.Call(fixture.tenant,f.p().subjectId(),f.input().runId(),f.input().sessionId(),f.candidate().incidentId(),"sha256:"+"c".repeat(64),1000,ModelSpend.Policy.mock(),PostgresAiInsightIT.NOW,PostgresAiInsightIT.NOW.plusSeconds(40),null,null);ledger.reserve(cost);
        var policy=policy(100,Set.of(),true);var p=store.preview(policy,f.p().subjectId(),now);assertEquals(List.of(1,2),p.batches().subList(0,2).stream().map(b->b.ids().size()).toList());assertTrue(p.batches().get(2).ids().size()>=4);
        var cmd=command(p);var receipt=store.apply(policy,f.p().subjectId(),cmd,()->{});
        assertEquals(receipt,store.receipt(fixture.tenant,cmd.requestId()).orElseThrow());assertEquals(receipt,store.apply(policy,f.p().subjectId(),cmd,()->{fail("Completed command must not repeat deletion");}));
        assertEquals(0,bodies("ai_control.ai_insight"));assertEquals(0,bodies("ai_control.evidence_snapshot"));assertEquals(1,fixture.count("ai_control.ai_insight"));assertEquals(2,fixture.count("ai_control.ai_insight_evidence"));assertEquals(1,fixture.count("ai_control.tool_read_session"));assertEquals(0,fixture.count("audit.tool_read_call"));
        assertEquals(cost,ledger.find(fixture.tenant,cost.runId()).orElseThrow());assertEquals(ToolFailure.Code.INPUT_CHANGED,assertThrows(ToolFailure.class,()->ledger.reserve(cost)).code());
        var marker=f.store().retired(fixture.tenant,f.input().runId()).orElseThrow();assertEquals(Set.of("missing.metric"),marker.metricKeys());assertEquals(f.candidate().requestDigest(),marker.requestDigest());assertTrue(f.store().find(fixture.tenant,f.input().runId()).isEmpty());
        assertEquals(ToolFailure.Code.EXPIRED,assertThrows(ToolFailure.class,()->f.service().get(f.p(),f.input().runId())).code());assertEquals(ToolFailure.Code.EXPIRED,assertThrows(ToolFailure.class,()->f.service().submit(f.p(),f.input())).code());assertEquals(ToolFailure.Code.EXPIRED,assertThrows(ToolFailure.class,()->f.store().save(f.candidate())).code());
        var denied=new Principal(f.p().subjectId(),fixture.tenant,Set.of(Permission.AI_INSIGHT_READ,Permission.INCIDENT_READ,Permission.ENTITY_READ),ResourceScope.tenantWide());assertEquals(ToolFailure.Code.FORBIDDEN,assertThrows(ToolFailure.class,()->f.service().get(denied,f.input().runId())).code());
        assertTrue(store.receipt(new TenantId("other"),cmd.requestId()).isEmpty());assertTrue(f.tools().retired(new TenantId("other"),f.input().evidenceIds().getFirst()).isEmpty());
        var dir=Path.of("../../.tmp/retention-pg");Files.createDirectories(dir);Files.writeString(dir.resolve("ai-retention-preview.json"),AiRetentionJson.JSON.writeValueAsString(Map.of("schemaVersion","1.0","storage","postgres","policy",AiRetentionJson.policy(policy),"preview",AiRetentionJson.preview(p))));Files.writeString(dir.resolve("ai-retention-receipt.json"),AiRetentionJson.encode(receipt));
    }
    @Test void holdsDisabledPoliciesAndConfigurationChangeProtectWholeBatch()throws Exception{
        save();var held=policy(100,Set.of(f.candidate().incidentId()),true);assertTrue(store.preview(held,f.p().subjectId(),now).batches().stream().allMatch(b->b.ids().isEmpty()));
        var disabled=policy(100,Set.of(),false);assertEquals(ToolFailure.Code.FORBIDDEN,assertThrows(ToolFailure.class,()->store.apply(disabled,f.p().subjectId(),command(store.preview(disabled,f.p().subjectId(),now)),()->{})).code());
        var policy=policy(100,Set.of(),true);var cmd=command(store.preview(policy,f.p().subjectId(),now));
        assertEquals(ToolFailure.Code.INPUT_CHANGED,assertThrows(ToolFailure.class,()->store.apply(policy,f.p().subjectId(),cmd,()->{throw new ToolFailure(ToolFailure.Code.INPUT_CHANGED);})).code());
        assertEquals(1,bodies("ai_control.ai_insight"));assertEquals(2,bodies("ai_control.evidence_snapshot"));assertTrue(fixture.count("audit.tool_read_call")>=4);assertTrue(store.receipt(fixture.tenant,cmd.requestId()).isEmpty());
    }
    @Test void boundedBatchesAndConcurrentReplayHaveOneReceipt()throws Exception{
        save();var policy=policy(1,Set.of(),true);var p=store.preview(policy,f.p().subjectId(),now);assertTrue(p.batches().get(1).hasMore());assertEquals(1,p.batches().get(1).ids().size());var cmd=command(p);
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->store.apply(policy,f.p().subjectId(),cmd,()->{}));var b=pool.submit(()->store.apply(policy,f.p().subjectId(),cmd,()->{}));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        assertEquals(1,bodies("ai_control.evidence_snapshot"));assertEquals(1,fixture.count("ai_control.retention_receipt"));
        assertEquals(ToolFailure.Code.INPUT_CHANGED,assertThrows(ToolFailure.class,()->store.apply(policy,new SubjectId("other"),cmd,()->{})).code());
        assertEquals(ToolFailure.Code.INPUT_CHANGED,assertThrows(ToolFailure.class,()->store.apply(policy,f.p().subjectId(),command(p),()->{})).code());
    }
    @Test void exactBoundaryUnexpiredAndStalePreviewCannotPurge()throws Exception{
        save();var policy=policy(100,Set.of(),true);var edge=PostgresAiInsightIT.NOW.plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);var edgeStore=new PostgresAiRetentionStore(PostgresAiInsightIT.source,Clock.fixed(edge,ZoneOffset.UTC));assertTrue(edgeStore.preview(policy,f.p().subjectId(),edge).batches().stream().allMatch(b->b.ids().isEmpty()));
        var cmd=command(store.preview(policy,f.p().subjectId(),now));var later=new PostgresAiRetentionStore(PostgresAiInsightIT.source,Clock.fixed(now.plusSeconds(120),ZoneOffset.UTC));
        assertEquals(ToolFailure.Code.EXPIRED,assertThrows(ToolFailure.class,()->later.apply(policy,f.p().subjectId(),cmd,()->{})).code());assertEquals(1,bodies("ai_control.ai_insight"));
    }
    @Test void evidenceFirstLeavesAuthorizationMetadataForLaterInsightPurge(){
        save();var policy=new AiRetention.Policy(fixture.tenant,"evidence-first",10,1,10,100,Set.of(),true);var p=store.preview(policy,f.p().subjectId(),now);store.apply(policy,f.p().subjectId(),command(p),()->{});assertTrue(f.store().find(fixture.tenant,f.input().runId()).isPresent());
        var next=policy(100,Set.of(),true);store.apply(next,f.p().subjectId(),command(store.preview(next,f.p().subjectId(),now)),()->{});assertEquals(Set.of("missing.metric"),f.store().retired(fixture.tenant,f.input().runId()).orElseThrow().metricKeys());
    }
}
