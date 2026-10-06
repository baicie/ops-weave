import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class SourceMetricDiscoverySmoke {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Metric discovery "+checks);}
    static void invalid(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected invalid metadata");}
    static void fails(WorkflowFailure.Code c,Runnable r){checks++;try{r.run();}catch(WorkflowFailure e){if(e.code()==c)return;throw e;}throw new AssertionError("Expected "+c);}
    static SourceMetricDiscovery.Item item(String id){return new SourceMetricDiscovery.Item(id,"101","fixture.cpu.usage.full.key","Fixture metric","","FLOAT","NO_MAPPING",null);}
    static SourceMetricDiscovery snapshot(List<SourceMetricDiscovery.Item> items,boolean complete){return new SourceMetricDiscovery(items,complete,"FIRST_PAGE_MATCH",complete?"READ_VERIFIED":"INCOMPLETE",SourceMetricDiscovery.digest(items));}
    public static void main(String[] args){
        var items=new ArrayList<>(List.of(item("1")));var d=snapshot(items,true);items.clear();check(d.items().size()==1);check(d.limit()==20);check(d.scope().equals("FIRST_ITEM_PAGE"));check(d.items().get(0).sourceUnit().isEmpty());
        invalid(()->snapshot(List.of(item("2"),item("1")),true));invalid(()->snapshot(List.of(item("1"),item("1")),true));invalid(()->snapshot(java.util.stream.IntStream.rangeClosed(1,21).mapToObj(n->item(""+n)).toList(),false));
        invalid(()->new SourceMetricDiscovery(d.items(),true,"UNVERIFIED","READ_VERIFIED",d.fingerprint()));invalid(()->new SourceMetricDiscovery(d.items(),false,"FIRST_PAGE_MATCH","READ_VERIFIED",d.fingerprint()));invalid(()->new SourceMetricDiscovery(d.items(),false,"UNVERIFIED","UNREACHABLE",d.fingerprint()));invalid(()->new SourceMetricDiscovery(d.items(),true,"FIRST_PAGE_MATCH","READ_VERIFIED","sha256:"+"0".repeat(64)));
        invalid(()->item("01"));invalid(()->new SourceMetricDiscovery.Item("1","2","x\n","Fixture","","FLOAT","NO_MAPPING",null));
        check(!SourceMetricDiscovery.compatible("BINARY","STRING"));check(SourceMetricDiscovery.compatible("UNSIGNED","DOUBLE"));check(snapshot(List.of(),true).complete()&&!SourceMetricDiscovery.unavailable().complete());
        var p=new Principal(new SubjectId("metric-fixture-owner"),new TenantId("metric-fixture-tenant"),Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());var store=new InMemoryWorkflowStore();String digest="sha256:"+"a".repeat(64);
        var config=new AtomicReference<>(new SourceSetupService.Connection(digest,"zabbix-jsonrpc",true));SourceSetupService.Connections connections=(who,source)->config.get();var clock=Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"),ZoneOffset.UTC);
        var created=new SourceSetupService(store,(who,target)->{throw new AssertionError();},connections,clock).confirm(p,new SourceSetupService.Command(UUID.randomUUID(),"Fixture metrics","",new WorkflowDefinition.Source("ZABBIX_HOST","fixture-metrics"),digest,null)).setup();var calls=new AtomicInteger();
        var service=new SourceInspectionService(store,connections,(who,source,kind)->{calls.incrementAndGet();check(kind.equals("DISCOVER_METRICS"));return new SourceInspectionService.Result(null,null,d);},clock);
        var command=new SourceInspectionService.Command(UUID.randomUUID(),1,digest);var first=service.run(p,created.id(),"DISCOVER_METRICS",command);check(first.validity().equals("CURRENT"));check(first.inspection().metricDiscovery().equals(d));check(service.run(p,created.id(),"DISCOVER_METRICS",command).equals(first));check(calls.get()==1);check(service.read(p,created.id(),command.requestId()).equals(first));
        fails(WorkflowFailure.Code.CONFLICT,()->service.run(p,created.id(),"DISCOVER",command));check(calls.get()==1);
        var denied=new Principal(p.subjectId(),p.tenantId(),p.permissions(),ResourceScope.of(Set.of(ResourceRef.source(p.tenantId(),"other-source"))));fails(WorkflowFailure.Code.FORBIDDEN,()->service.run(denied,created.id(),"DISCOVER_METRICS",new SourceInspectionService.Command(UUID.randomUUID(),1,digest)));check(calls.get()==1);
        config.set(new SourceSetupService.Connection(digest,"zabbix-jsonrpc",false));check(service.read(p,created.id(),command.requestId()).validity().equals("UNVERIFIED"));fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->service.run(p,created.id(),"DISCOVER_METRICS",new SourceInspectionService.Command(UUID.randomUUID(),1,digest)));check(calls.get()==1);config.set(new SourceSetupService.Connection(digest,"zabbix-jsonrpc",true));
        var partial=new SourceInspectionService(store,connections,(who,source,kind)->new SourceInspectionService.Result(null,null,snapshot(List.of(item("1")),false)),clock);check(partial.run(p,created.id(),"DISCOVER_METRICS",new SourceInspectionService.Command(UUID.randomUUID(),1,digest)).validity().equals("UNVERIFIED"));
        var failed=new SourceInspectionService(store,connections,(who,source,kind)->{throw new IllegalStateException("Fixture source failure");},clock);var failure=failed.run(p,created.id(),"DISCOVER_METRICS",new SourceInspectionService.Command(UUID.randomUUID(),1,digest));check(failure.inspection().metricDiscovery().statusCode().equals("UNREACHABLE"));check(failure.validity().equals("UNVERIFIED"));check(failure.inspection().dataMode().equals("zabbix-jsonrpc"));check(service.recent(p,created.id()).size()==3);
        System.out.println("Source metric discovery smoke: "+checks+" checks passed");
    }
}
