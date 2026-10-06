import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.MetricMappingService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcConnector;
import com.acme.opsweave.integration.infrastructure.ZabbixJsonRpcHistoryReader;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.telemetry.domain.*;
import com.acme.opsweave.telemetry.infrastructure.InMemoryMetricDefinitionStore;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class MetricMappingSmoke {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Mapping check "+checks);}
    static void rejected(MetricMappingFailure.Code code,Runnable operation){try{operation.run();throw new AssertionError("Not rejected");}catch(MetricMappingFailure e){check(e.code()==code);}}
    static void invalid(Runnable operation){try{operation.run();throw new AssertionError("Not rejected");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    static MappingDefinition mapping(int rev,BigDecimal maximum){return new MappingDefinition("fixture-cpu-mapping","zabbix","system.cpu.util[,user]","host.cpu.usage.user","Fixture CPU",MetricType.GAUGE,"1",MetricValueType.DOUBLE,List.of("mode"),Map.of("mode","user"),"multiply:0.01",rev,BigDecimal.ZERO,maximum);}
    static final TenantId tenant=new TenantId("mapping-domain-fixture");
    static final Principal principal=new Principal(new SubjectId("mapping-owner"),tenant,Set.of(Permission.METRIC_READ,Permission.ENTITY_READ,Permission.SOURCE_SYNC,Permission.SOURCE_CONFIGURE),ResourceScope.tenantWide());
    static final Map<String,Object> item=Map.of("itemid","20001","hostid","10084","key_","system.cpu.util[,user]","name","Fixture CPU","value_type","0","units","%");
    static MetricMappingService service(InMemoryMetricDefinitionStore store,MappingDefinition mapping){return new MetricMappingService(store,new MappingRegistry(List.of(mapping)),Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"),ZoneOffset.UTC));}
    static MetricBinding unpinned(MetricBinding b){return new MetricBinding(b.tenantId(),b.sourceType(),b.sourceInstanceId(),b.externalItemId(),b.entityId(),b.hostExternalId(),b.metricKey(),b.fixedDimensions(),b.sourceUnit(),b.valueTransform(),b.mappingRevision(),b.lifecycle(),b.version());}
    public static void main(String[] args){
        var original=mapping(1,BigDecimal.ONE);var changed=mapping(1,new BigDecimal("0.9"));var upgrade=mapping(2,new BigDecimal("0.9"));
        check(!original.pin().equals(changed.pin()));check(!original.pin().equals(upgrade.pin()));check(original.pin().equals(mapping(1,BigDecimal.ONE).pin()));
        invalid(()->new MetricMappingPin("../invalid",1,original.pin().digest()));invalid(()->new MetricMappingPin(original.id(),0,original.pin().digest()));invalid(()->new MetricMappingPin(original.id(),1,"bad"));
        invalid(()->new MappingRegistry(List.of(original,changed)));
        var mapped=new ZabbixItemMapper(new MappingRegistry(List.of(original))).map(tenant,"fixture-source",item);var store=new InMemoryMetricDefinitionStore();
        store.upsert(mapped.definition());store.upsert(unpinned(mapped.binding()));store.upsert(mapped.binding());
        check(store.findBinding(tenant,"fixture-source","20001").orElseThrow().mappingPin()==null);check(store.findBinding(tenant,"fixture-source","20001").orElseThrow().version()==1);
        var s=service(store,original);var view=s.read(principal,"fixture-source","20001");check(view.canConfigure());check(view.candidates().size()==1);
        var command=new MetricMappingService.Command(UUID.randomUUID(),1,original.pin());var receipt=s.write(principal,"fixture-source","20001",command);
        check(receipt.previousPin()==null);check(receipt.binding().version()==2);check(receipt.binding().mappingPin().equals(original.pin()));
        check(receipt.equals(s.write(principal,"fixture-source","20001",command)));check(receipt.equals(s.receipt(principal,"fixture-source","20001",command.requestId())));
        check(service(store,changed).write(principal,"fixture-source","20001",command).equals(receipt));
        rejected(MetricMappingFailure.Code.CONFLICT,()->s.write(principal,"fixture-source","20001",new MetricMappingService.Command(command.requestId(),2,original.pin())));
        rejected(MetricMappingFailure.Code.CONFLICT,()->s.write(principal,"fixture-source","20001",new MetricMappingService.Command(UUID.randomUUID(),1,original.pin())));
        store.upsert(mapped.binding());check(store.findBinding(tenant,"fixture-source","20001").orElseThrow().equals(receipt.binding()));
        var bad=new ZabbixItemMapper(new MappingRegistry(List.of(changed))).map(tenant,"fixture-source",item);
        invalid(()->store.upsert(bad.binding()));check(store.findBinding(tenant,"fixture-source","20001").orElseThrow().equals(receipt.binding()));
        rejected(MetricMappingFailure.Code.INCOMPATIBLE,()->service(store,changed).write(principal,"fixture-source","20001",new MetricMappingService.Command(UUID.randomUUID(),2,changed.pin())));
        var readerPrincipal=new Principal(new SubjectId("viewer"),tenant,Set.of(Permission.METRIC_READ,Permission.ENTITY_READ,Permission.SOURCE_SYNC),ResourceScope.tenantWide());
        check(!s.read(readerPrincipal,"fixture-source","20001").canConfigure());rejected(MetricMappingFailure.Code.FORBIDDEN,()->s.write(readerPrincipal,"fixture-source","20001",new MetricMappingService.Command(UUID.randomUUID(),2,original.pin())));
        rejected(MetricMappingFailure.Code.NOT_FOUND,()->s.receipt(readerPrincipal,"fixture-source","20001",command.requestId()));
        var foreign=new Principal(principal.subjectId(),new TenantId("foreign-mapping-fixture"),principal.permissions(),ResourceScope.tenantWide());check(s.list(foreign).items().isEmpty());
        rejected(MetricMappingFailure.Code.NOT_FOUND,()->s.receipt(foreign,"fixture-source","20001",command.requestId()));
        var excluded=new Principal(principal.subjectId(),tenant,principal.permissions(),ResourceScope.of(Set.of(ResourceRef.source(tenant,"different-source"))));check(s.list(excluded).items().isEmpty());
        rejected(MetricMappingFailure.Code.FORBIDDEN,()->s.read(excluded,"fixture-source","20001"));
        var advanced=service(store,upgrade).write(principal,"fixture-source","20001",new MetricMappingService.Command(UUID.randomUUID(),2,upgrade.pin()));check(advanced.binding().version()==3);check(advanced.binding().mappingRevision()==2);
        check(service(store,upgrade).write(principal,"fixture-source","20001",command).equals(receipt));
        var unchanged=service(store,upgrade).write(principal,"fixture-source","20001",new MetricMappingService.Command(UUID.randomUUID(),3,upgrade.pin()));check(unchanged.binding().version()==3);check(unchanged.previousPin().equals(upgrade.pin()));
        rejected(MetricMappingFailure.Code.INCOMPATIBLE,()->s.write(principal,"fixture-source","20001",new MetricMappingService.Command(UUID.randomUUID(),3,original.pin())));
        var wrongDefinition=new MetricDefinition(tenant,original.metricKey(),"Fixture","seconds",MetricValueType.DOUBLE,MetricType.GAUGE,List.of("mode"),1);invalid(()->store.upsert(wrongDefinition));
        var calls=new AtomicInteger();var secrets=new AtomicInteger();var transport=new ZabbixJsonRpcConnector.Transport(){
            public String exchange(URI url,String body,String token){calls.incrementAndGet();return "[]";}
            public List<Map<String,Object>> readHostArray(String body){return List.of(item);}
        };
        var source=new Connector.SourceContext(tenant,"fixture-source","fixture-secret-ref");var window=new HistoryWindow(10,20,null,20);
        for(var b:List.of(unpinned(mapped.binding()),mapped.binding())){
            var history=new ZabbixJsonRpcHistoryReader(URI.create("http://127.0.0.1:1"),transport,ref->{secrets.incrementAndGet();return "labeled-fixture-token";},new MappingRegistry(List.of(changed)));
            try{history.read(source,b,window);throw new AssertionError("History not rejected");}catch(HistoryReadException e){check(e.code()==HistoryReadException.Code.METADATA_CHANGED);}
        }
        check(calls.get()==0);check(secrets.get()==0);
        check(service(store,upgrade).list(principal).items().size()==1);store.retireMissing(tenant,"fixture-source",Set.of());
        check(store.findBinding(tenant,"fixture-source","20001").orElseThrow().mappingPin().equals(upgrade.pin()));
        rejected(MetricMappingFailure.Code.CONFLICT,()->service(store,upgrade).write(principal,"fixture-source","20001",new MetricMappingService.Command(UUID.randomUUID(),4,upgrade.pin())));
        System.out.println("Metric mapping pin/maintenance smoke: "+checks+" checks passed");
    }
}
