import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class SourceMetricPageSmoke {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Metric page "+checks);}
    static void fails(WorkflowFailure.Code code,Runnable work){checks++;try{work.run();}catch(WorkflowFailure e){if(e.code()==code)return;throw e;}throw new AssertionError("Expected "+code);}
    static void invalid(Runnable work){checks++;try{work.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected invalid page");}
    static SourceMetricDiscovery.Item item(String id){return new SourceMetricDiscovery.Item(id,"101","fixture.metric.full.key."+id,"Fixture metric "+id,"","FLOAT","NO_MAPPING",null);}
    static final class MutableClock extends Clock {Instant value=Instant.parse("2026-10-03T12:00:00Z");public Instant instant(){return value;}public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId zone){return this;}}
    public static void main(String[] args){
        var clock=new MutableClock();var ids=java.util.stream.IntStream.rangeClosed(1,45).mapToObj(Integer::toString).toList();var m=SourceMetricPage.Manifest.capture(UUID.randomUUID(),clock.instant(),ids);
        var first=SourceMetricPage.verified(m,0,ids.subList(0,20).stream().map(SourceMetricPageSmoke::item).toList(),"ITEMID_WATERMARK");check(!first.complete());check(first.nextOffset()==20);check(m.total()==45);
        var last=SourceMetricPage.verified(m,40,ids.subList(40,45).stream().map(SourceMetricPageSmoke::item).toList(),"ITEMID_WATERMARK");check(last.complete());check(last.nextOffset()==null);
        invalid(()->SourceMetricPage.verified(m,20,first.items(),"ITEMID_WATERMARK"));invalid(()->SourceMetricPage.verified(m,1,first.items(),"ITEMID_WATERMARK"));invalid(()->SourceMetricPage.Manifest.capture(UUID.randomUUID(),clock.instant(),List.of("2","1")));invalid(()->SourceMetricPage.Manifest.capture(UUID.randomUUID(),clock.instant(),List.of("1","1")));invalid(()->SourceMetricPage.Manifest.capture(UUID.randomUUID(),clock.instant(),Collections.nCopies(1001,"1")));
        for(var code:List.of("MEMBERSHIP_CHANGED","CAPACITY","UNREACHABLE")){var failure=SourceMetricPage.failed(m,20,code);check(failure.items().isEmpty()&&!failure.complete()&&failure.nextOffset()==null);}
        var empty=SourceMetricPage.verified(SourceMetricPage.Manifest.capture(UUID.randomUUID(),clock.instant(),List.of()),0,List.of(),"ITEMID_WATERMARK");check(empty.complete());check(!SourceMetricPage.failed(null,0,"UNREACHABLE").complete());
        var p=new Principal(new SubjectId("metric-page-fixture-owner"),new TenantId("metric-page-fixture-tenant"),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());var store=new InMemoryWorkflowStore();String digest="sha256:"+"a".repeat(64);
        var connection=new AtomicReference<>(new SourceSetupService.Connection(digest,"zabbix-jsonrpc",true));SourceSetupService.Connections connections=(who,source)->connection.get();
        var setup=new SourceSetupService(store,(who,target)->{throw new AssertionError();},connections,clock).confirm(p,new SourceSetupService.Command(UUID.randomUUID(),"Fixture metric inventory","",new WorkflowDefinition.Source("ZABBIX_HOST","fixture-metric-page"),digest,null)).setup();var calls=new AtomicInteger();var fail=new AtomicBoolean();var late=new AtomicBoolean();
        var reader=new SourceInspectionService.Reader(){
            public SourceInspectionService.Result read(Principal who,WorkflowDefinition.Source source,String kind){throw new AssertionError();}
            public SourceMetricPage readMetricPage(Principal who,SourceInstance instance,SourceInspection pending,SourceMetricPage previous){
                calls.incrementAndGet();if(fail.get())throw new IllegalStateException("Explicit fixture failure");var manifest=previous==null?SourceMetricPage.Manifest.capture(pending.requestId(),pending.asOf(),ids):previous.manifest();int offset=previous==null?0:previous.nextOffset();
                if(late.get())clock.value=clock.value.plusSeconds(66);
                return SourceMetricPage.verified(manifest,offset,ids.subList(offset,Math.min(offset+20,ids.size())).stream().map(SourceMetricPageSmoke::item).toList(),"ITEMID_WATERMARK");
            }
        };
        var service=new SourceInspectionService(store,connections,reader,clock);var c=new SourceInspectionService.Command(UUID.randomUUID(),1,digest,null);var a=service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",c);check(a.validity().equals("CURRENT"));check(!a.inspection().metricPage().complete());check(service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",c).equals(a));check(calls.get()==1);
        fails(WorkflowFailure.Code.CONFLICT,()->service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",new SourceInspectionService.Command(c.requestId(),1,digest,UUID.randomUUID())));check(calls.get()==1);
        clock.value=clock.value.plusSeconds(60);var c2=new SourceInspectionService.Command(UUID.randomUUID(),1,digest,c.requestId());var b=service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",c2);check(b.inspection().metricPage().offset()==20);check(b.inspection().previousRequestId().equals(c.requestId()));check(b.inspection().metricPage().manifest().equals(a.inspection().metricPage().manifest()));check(service.read(p,setup.id(),c2.requestId()).equals(b));
        var c3=new SourceInspectionService.Command(UUID.randomUUID(),1,digest,c2.requestId());var z=service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",c3);check(z.inspection().metricPage().items().size()==5&&z.inspection().metricPage().complete());check(calls.get()==3);
        fails(WorkflowFailure.Code.CONFLICT,()->service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",new SourceInspectionService.Command(UUID.randomUUID(),1,digest,c3.requestId())));check(calls.get()==3);
        var denied=new Principal(p.subjectId(),p.tenantId(),p.permissions(),ResourceScope.of(Set.of(ResourceRef.source(p.tenantId(),"other-source"))));fails(WorkflowFailure.Code.FORBIDDEN,()->service.run(denied,setup.id(),"DISCOVER_METRIC_PAGE",new SourceInspectionService.Command(UUID.randomUUID(),1,digest,c.requestId())));check(calls.get()==3);
        var other=new Principal(new SubjectId("metric-page-other"),p.tenantId(),p.permissions(),p.resourceScope());fails(WorkflowFailure.Code.NOT_FOUND,()->service.read(other,setup.id(),c.requestId()));check(calls.get()==3);
        var otherSetup=new SourceSetupService(store,(who,target)->{throw new AssertionError();},connections,clock).confirm(p,new SourceSetupService.Command(UUID.randomUUID(),"Fixture second inventory","",new WorkflowDefinition.Source("ZABBIX_HOST","fixture-metric-other"),digest,null)).setup();fails(WorkflowFailure.Code.NOT_FOUND,()->service.run(p,otherSetup.id(),"DISCOVER_METRIC_PAGE",new SourceInspectionService.Command(UUID.randomUUID(),1,digest,c.requestId())));check(calls.get()==3);
        var foreign=new Principal(p.subjectId(),new TenantId("metric-page-foreign-tenant"),p.permissions(),p.resourceScope());fails(WorkflowFailure.Code.NOT_FOUND,()->service.read(foreign,setup.id(),c.requestId()));check(calls.get()==3);
        connection.set(new SourceSetupService.Connection(digest,"zabbix-jsonrpc",false));fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",new SourceInspectionService.Command(UUID.randomUUID(),1,digest,c.requestId())));check(calls.get()==3);connection.set(new SourceSetupService.Connection(digest,"zabbix-jsonrpc",true));
        fail.set(true);var f=service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",new SourceInspectionService.Command(UUID.randomUUID(),1,digest,c.requestId()));check(f.validity().equals("UNVERIFIED"));check(f.inspection().metricPage().offset()==20&&f.inspection().metricPage().items().isEmpty());check(service.read(p,setup.id(),f.inspection().requestId()).equals(f));fail.set(false);
        late.set(true);var unknown=service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",new SourceInspectionService.Command(UUID.randomUUID(),1,digest,c.requestId()));check(unknown.inspection().state().equals("UNKNOWN"));check(unknown.inspection().metricPage()==null);check(unknown.inspection().previousRequestId().equals(c.requestId()));late.set(false);
        int before=calls.get();clock.value=a.inspection().metricPage().manifest().expiresAt();check(service.read(p,setup.id(),c2.requestId()).validity().equals("EXPIRED"));fails(WorkflowFailure.Code.CONFLICT,()->service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",new SourceInspectionService.Command(UUID.randomUUID(),1,digest,c.requestId())));check(calls.get()==before);check(service.run(p,setup.id(),"DISCOVER_METRIC_PAGE",c).validity().equals("EXPIRED"));check(calls.get()==before);
        System.out.println("Source metric page smoke: "+checks+" checks passed");
    }
}
