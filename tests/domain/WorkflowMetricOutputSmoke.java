import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowMetricOutput.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicit protocol-shaped synthetic input and an in-memory sink; no real provider/storage claim. */
public final class WorkflowMetricOutputSmoke {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Output check "+checks);}
    static void fail(WorkflowFailure.Code code,Runnable work){checks++;try{work.run();}catch(WorkflowFailure failure){if(failure.code()==code)return;throw failure;}throw new AssertionError("Expected "+code);}
    static final class MemorySink implements WorkflowMetricOutputService.Sink {
        MetricWriteBatch batch;List<MetricWriteBatch.Sample> visible=List.of();int writes,reads;boolean unknown,rejected;
        public void write(MetricWriteBatch batch){writes++;this.batch=batch;if(rejected)throw new WorkflowMetricOutputService.OutputFailure(false);visible=unknown?batch.samples().subList(0,1):batch.samples();if(unknown)throw new WorkflowMetricOutputService.OutputFailure(true);}
        public List<MetricWriteBatch.Sample> read(Map<String,String> labels,List<Long> timestamps){reads++;return visible;}
    }
    static final class Fixture {
        final Clock clock=Clock.fixed(Instant.parse("2026-10-04T00:00:02Z"),ZoneOffset.UTC);
        final InMemoryWorkflowStore store=new InMemoryWorkflowStore();final MemorySink sink=new MemorySink();
        final Principal p=new Principal(new SubjectId("output-fixture"),new TenantId("output-fixture"),Set.of(Permission.SOURCE_SYNC,Permission.METRIC_READ),ResourceScope.tenantWide());
        final WorkflowService workflows;final WorkflowDefinition d;final Command command;int guards;int revokeAt=Integer.MAX_VALUE;
        Fixture()throws Exception {
            var mapping=MappingDocumentParser.parse(Files.readString(Path.of("extensions/mappings/zabbix-cpu-user.yaml")));
            var item=new SourceMetricDiscovery.Item("50740","10683",mapping.itemKeyExact(),"Fixture CPU","%","FLOAT","MAPPED",SourceMetricDiscovery.Mapping.from(mapping));
            var source=new WorkflowDefinition.Source("ZABBIX_METRIC","fixture-source",new WorkflowDefinition.ConfigurationPin(UUID.randomUUID(),1,"sha256:"+"a".repeat(64)),WorkflowMetricSourcePin.from(UUID.randomUUID(),item));
            d=WorkflowMetricSourceSmoke.flow("output-fixture",source,mapping);
            var samples=List.of(WorkflowStandardMetricSmoke.sample(mapping,"12.5"),Map.<String,Object>of("timestamp","2026-10-04T00:00:01Z","sourceKey",mapping.itemKeyExact(),"value","15"));
            var sources=new WorkflowService.Sources(){public void require(Principal principal,WorkflowDefinition.Source source,com.acme.opsweave.integration.api.WorkflowStore.Session s,boolean available){if(available&&++guards>=revokeAt)throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);}public void requireTarget(Principal principal,WorkflowDefinition.Source source,WorkflowDefinition.Target target,com.acme.opsweave.integration.api.WorkflowStore.Session s,boolean available){if(!source.metric().sourceKey().equals(mapping.itemKeyExact())||!target.mappingPin().equals(mapping.pin()))throw new AssertionError("Fixture pins must match");}};
            workflows=new WorkflowService(store,(principal,target)->{throw new AssertionError("No entity catalog");},(principal,s,id)->new WorkflowService.Batch(samples,"zabbix-jsonrpc",2,0,false,"SUCCEEDED"),sources,(principal,pin)->mapping,clock);
            workflows.save(p,d,WorkflowSmoke.layout(d),0);var preview=workflows.evaluate(p,d.id(),1,1,d.digest(),false,null,null);workflows.publish(p,d.id(),1,1,d.digest(),preview.receipt().id());
            var tested=workflows.evaluate(p,d.id(),1,0,d.digest(),true,null,null);command=new Command(UUID.randomUUID(),d.id(),1,d.digest(),tested.receipt().id(),samples);guards=0;
        }
        WorkflowMetricOutputService service(){return new WorkflowMetricOutputService(store,workflows,sink,clock);}
    }
    public static void main(String[] args)throws Exception {
        var fixture=new Fixture();var service=fixture.service();var receipt=service.write(fixture.p,fixture.command);
        check(receipt.state().equals("CONFIRMED")&&receipt.confirmed()==2&&receipt.unknown()==0);
        check(fixture.sink.writes==1);check(fixture.sink.batch.samples().getFirst().value().toPlainString().equals("0.125"));
        check(receipt.labels().get("dim_mode").equals("user"));check(!receipt.labels().containsKey("request_id"));check(!receipt.labels().containsKey("entity_id"));
        check(receipt.equals(service.write(fixture.p,fixture.command)));check(fixture.sink.writes==1);
        check(receipt.equals(service.read(fixture.p,receipt.requestId())));check(fixture.service().data(fixture.p,receipt.requestId()).proofMatches());
        check(service.records(fixture.p,fixture.d.id()).items().equals(List.of(receipt)));check(service.records(fixture.p,fixture.d.id()).truncated()==false);
        check(receipt.commandDigest().equals(fixture.command.commandDigest()));check(WorkflowMetricOutput.WorkflowServiceInput.digest(fixture.command.samples()).equals(WorkflowService.inputDigest(fixture.command.samples(),null)));
        var changed=new Command(receipt.requestId(),fixture.d.id(),1,fixture.d.digest(),fixture.command.previewId(),List.of(Map.of("timestamp","2026-10-04T00:00:00Z","sourceKey",fixture.d.source().metric().sourceKey(),"value","13")));
        fail(WorkflowFailure.Code.CONFLICT,()->service.write(fixture.p,changed));check(fixture.sink.writes==1);
        var other=new Principal(new SubjectId("other"),fixture.p.tenantId(),fixture.p.permissions(),ResourceScope.tenantWide());fail(WorkflowFailure.Code.NOT_FOUND,()->service.read(other,receipt.requestId()));
        check(service.records(other,fixture.d.id()).items().isEmpty());
        var tenant=new Principal(fixture.p.subjectId(),new TenantId("other-output"),fixture.p.permissions(),ResourceScope.tenantWide());fail(WorkflowFailure.Code.NOT_FOUND,()->service.read(tenant,receipt.requestId()));
        var noRead=new Principal(fixture.p.subjectId(),fixture.p.tenantId(),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());fail(WorkflowFailure.Code.FORBIDDEN,()->service.read(noRead,receipt.requestId()));
        var expired=new WorkflowMetricOutputService(fixture.store,fixture.workflows,fixture.sink,Clock.offset(fixture.clock,Duration.ofSeconds(901)));
        fail(WorkflowFailure.Code.PREVIEW_REQUIRED,()->expired.write(fixture.p,new Command(UUID.randomUUID(),fixture.command.id(),1,fixture.command.digest(),fixture.command.previewId(),fixture.command.samples())));check(fixture.sink.writes==1);
        var uncertain=new Fixture();uncertain.sink.unknown=true;var unknown=uncertain.service().write(uncertain.p,uncertain.command);
        check(unknown.state().equals("UNKNOWN")&&unknown.unknown()==2&&unknown.confirmed()==0);check(uncertain.sink.visible.size()==1);
        check(!uncertain.service().data(uncertain.p,unknown.requestId()).proofMatches());
        check(uncertain.service().confirm(uncertain.p,unknown.requestId()).state().equals("UNKNOWN"));check(uncertain.sink.writes==1);
        check(uncertain.service().write(uncertain.p,uncertain.command).state().equals("UNKNOWN"));check(uncertain.sink.writes==1);
        uncertain.sink.visible=uncertain.sink.batch.samples();var confirmed=uncertain.service().confirm(uncertain.p,unknown.requestId());
        check(confirmed.state().equals("CONFIRMED")&&confirmed.confirmed()==2);check(uncertain.sink.writes==1);check(uncertain.service().confirm(uncertain.p,unknown.requestId()).equals(confirmed));
        var rejected=new Fixture();rejected.sink.rejected=true;var denied=rejected.service().write(rejected.p,rejected.command);check(denied.state().equals("FAILED")&&denied.unknown()==0&&denied.confirmed()==0);check(rejected.sink.writes==1);
        var revoked=new Fixture();revoked.revokeAt=2;var refusal=revoked.service().write(revoked.p,revoked.command);check(refusal.state().equals("FAILED")&&refusal.error().equals("FORBIDDEN"));check(revoked.sink.writes==0);
        check(revoked.service().write(revoked.p,revoked.command).equals(refusal));check(revoked.sink.writes==0);
        for(boolean wrongOrigin:List.of(true,false)){
            var invalid=new Fixture();var original=invalid.workflows.run(invalid.p,invalid.command.previewId());var r=original.receipt();var t=original.trace();
            var record=new com.acme.opsweave.integration.api.WorkflowStore.Receipt(UUID.randomUUID(),r.digest(),r.inputDigest(),wrongOrigin?"fixture":"zabbix-jsonrpc",r.accepted(),r.rejected(),r.filtered(),r.createdAt());
            var target=wrongOrigin?t.target():new WorkflowDefinition.Target(null,1,null,"METRIC",t.target().mappingPin(),"host.cpu.invalid");
            var trace=new WorkflowTrace(t.source(),target,t.syncRunId(),t.startedAt(),t.durationMillis(),t.retainedCount(),t.missingRaw(),t.truncated(),t.sourceStatus(),t.dryRun(),t.writesPerformed(),t.rows());
            invalid.store.transaction(invalid.p.tenantId(),s->{s.addRun(invalid.p.subjectId().value(),new com.acme.opsweave.integration.api.WorkflowStore.Run(invalid.d.id(),1,"RUN",record,trace));return null;});
            fail(WorkflowFailure.Code.PREVIEW_REQUIRED,()->invalid.service().write(invalid.p,new Command(UUID.randomUUID(),invalid.d.id(),1,invalid.d.digest(),record.id(),invalid.command.samples())));check(invalid.sink.writes==0);
        }
        System.out.println("WorkflowMetricOutputSmoke: "+checks+" checks passed");
    }
}
