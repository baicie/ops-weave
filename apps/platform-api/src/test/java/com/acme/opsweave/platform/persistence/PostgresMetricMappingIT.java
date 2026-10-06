package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.integration.application.MetricMappingService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.ClasspathMappingCatalog;
import com.acme.opsweave.telemetry.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresMetricMappingIT extends OwnedInventoryTest {
    final TenantId tenant=new TenantId("mapping-pg-fixture-"+UUID.randomUUID());
    final Principal p=new Principal(new SubjectId("mapping-fixture-owner"),tenant,Set.of(Permission.METRIC_READ,Permission.ENTITY_READ,Permission.SOURCE_SYNC,Permission.SOURCE_CONFIGURE),ResourceScope.tenantWide());
    final DriverManagerDataSource db=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
    final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","fixture-source",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
    final MappingDefinition mapping=ClasspathMappingCatalog.load(getClass().getClassLoader()).find("zabbix","system.cpu.util[,user]").orElseThrow();
    MetricMappingService service(MappingDefinition m){return new MetricMappingService(wiring.metricMappings(),new MappingRegistry(List.of(m)),Clock.systemUTC());}
    MetricBinding seed(String source,String id,boolean legacy){
        var mapped=new ZabbixItemMapper(new MappingRegistry(List.of(mapping))).map(tenant,source,Map.of("itemid",id,"hostid","10084","key_",mapping.itemKeyExact(),"name","Explicit PG fixture","value_type","0","units","%"));
        var b=mapped.binding();wiring.metrics().upsert(mapped.definition());
        if(legacy)b=new MetricBinding(b.tenantId(),b.sourceType(),b.sourceInstanceId(),b.externalItemId(),b.entityId(),b.hostExternalId(),b.metricKey(),b.fixedDimensions(),b.sourceUnit(),b.valueTransform(),b.mappingRevision(),b.lifecycle(),b.version());
        wiring.metrics().upsert(b);return b;
    }
    @Test void existingLegacyIsNeverAdoptedBySourceRefreshAndOriginalReceiptsSurviveRestart() {
        var old=seed("fixture-source","20001",true);var incoming=seed("fixture-source","20001",false);
        assertNull(wiring.metrics().findBinding(tenant,"fixture-source","20001").orElseThrow().mappingPin());
        assertEquals(1,wiring.metrics().findBinding(tenant,"fixture-source","20001").orElseThrow().version());
        var c=new MetricMappingService.Command(UUID.randomUUID(),old.version(),mapping.pin());var original=service(mapping).write(p,"fixture-source","20001",c);
        assertEquals(2,original.binding().version());assertEquals(original,service(mapping).write(p,"fixture-source","20001",c));
        var reopened=new MetricMappingService(new PostgresMetricDefinitionStore(db),new MappingRegistry(List.of(mapping)),Clock.systemUTC());
        assertEquals(original,reopened.receipt(p,"fixture-source","20001",c.requestId()));
        wiring.metrics().upsert(incoming);assertEquals(original.binding(),wiring.metrics().findBinding(tenant,"fixture-source","20001").orElseThrow());
        assertThrows(MetricMappingFailure.class,()->service(mapping).write(p,"fixture-source","20001",new MetricMappingService.Command(UUID.randomUUID(),1,mapping.pin())));
        var next=new MappingDefinition(mapping.id(),mapping.connector(),mapping.itemKeyExact(),mapping.metricKey(),mapping.displayName(),mapping.metricType(),mapping.unit(),mapping.valueType(),mapping.dimensionSchema(),mapping.fixedDimensions(),mapping.valueTransform(),2,mapping.minimum(),new BigDecimal("0.9"));
        var advanced=service(next).write(p,"fixture-source","20001",new MetricMappingService.Command(UUID.randomUUID(),2,next.pin()));assertEquals(3,advanced.binding().version());assertEquals(2,advanced.binding().mappingRevision());
        assertEquals(original,service(next).write(p,"fixture-source","20001",c));
        wiring.metrics().retireMissing(tenant,"fixture-source",Set.of());assertEquals(next.pin(),wiring.metrics().findBinding(tenant,"fixture-source","20001").orElseThrow().mappingPin());
    }
    @Test void independentAdaptersSerializeCasAndSameRequestOnlyHasOneEffect()throws Exception {
        seed("fixture-source","20001",true);var c=new MetricMappingService.Command(UUID.randomUUID(),1,mapping.pin());
        try(var executor=Executors.newFixedThreadPool(4)){
            var gate=new CountDownLatch(1);var futures=new ArrayList<Future<MetricMappingReceipt>>();
            for(int i=0;i<4;i++)futures.add(executor.submit(()->{gate.await();return new MetricMappingService(new PostgresMetricDefinitionStore(db),new MappingRegistry(List.of(mapping)),Clock.systemUTC()).write(p,"fixture-source","20001",c);}));
            gate.countDown();var original=futures.getFirst().get(15,TimeUnit.SECONDS);for(var f:futures)assertEquals(original,f.get(15,TimeUnit.SECONDS));
        }
        assertEquals(2,wiring.metrics().findBinding(tenant,"fixture-source","20001").orElseThrow().version());
        wiring.metricMappings().mappingTransaction(tenant,s->{assertEquals(1,s.receiptCount(p.subjectId().value()));return null;});
    }
    @Test void scopedReadsDoNotLoseAllowedRowsBehindExcludedRowsAndRefreshRejectsChangedDefinition() {
        for(int i=1;i<=205;i++)seed("aaa-excluded",Integer.toString(i),true);
        var b=seed("zzz-allowed","20001",false);
        var scope=ResourceScope.of(Set.of(ResourceRef.source(tenant,"zzz-allowed"),ResourceRef.entity(tenant,b.entityId()),ResourceRef.metric(tenant,b.metricKey())));
        var limited=new Principal(p.subjectId(),tenant,p.permissions(),scope);var page=service(mapping).list(limited);assertEquals(1,page.items().size());assertFalse(page.truncated());assertEquals("zzz-allowed",page.items().getFirst().binding().sourceInstanceId());
        var changed=new MappingDefinition(mapping.id(),mapping.connector(),mapping.itemKeyExact(),mapping.metricKey(),mapping.displayName(),mapping.metricType(),mapping.unit(),mapping.valueType(),mapping.dimensionSchema(),mapping.fixedDimensions(),mapping.valueTransform(),1,mapping.minimum(),new BigDecimal("0.9"));
        var next=new ZabbixItemMapper(new MappingRegistry(List.of(changed))).map(tenant,"zzz-allowed",Map.of("itemid","20001","hostid","10084","key_",changed.itemKeyExact(),"name","Explicit fixture","value_type","0","units","%"));
        assertThrows(IllegalStateException.class,()->wiring.metrics().upsert(next.binding()));
        assertEquals(b,wiring.metrics().findBinding(tenant,"zzz-allowed","20001").orElseThrow());
        assertThrows(IllegalStateException.class,()->wiring.metrics().upsert(new MetricDefinition(tenant,b.metricKey(),"Fixture","seconds",MetricValueType.DOUBLE,MetricType.GAUGE,List.of("mode"),1)));
        assertEquals("1",wiring.metrics().find(tenant,b.metricKey()).orElseThrow().unit());
    }
    @Test void corruptedOriginalReceiptFailsClosedAndOwnerDoesNotSubstitute()throws Exception {
        seed("fixture-source","20001",true);var c=new MetricMappingService.Command(UUID.randomUUID(),1,mapping.pin());service(mapping).write(p,"fixture-source","20001",c);
        var other=new Principal(new SubjectId("another-owner"),tenant,p.permissions(),ResourceScope.tenantWide());
        var e=assertThrows(MetricMappingFailure.class,()->service(mapping).receipt(other,"fixture-source","20001",c.requestId()));assertEquals(MetricMappingFailure.Code.NOT_FOUND,e.code());
        try(var connection=db.getConnection();var statement=connection.prepareStatement("UPDATE telemetry.metric_mapping_command SET body=jsonb_set(body,'{commandDigest}',to_jsonb(?::text)) WHERE tenant_id=? AND request_id=?")){
            statement.setString(1,"sha256:"+"0".repeat(64));statement.setString(2,tenant.value());statement.setObject(3,c.requestId());assertEquals(1,statement.executeUpdate());
        }
        assertThrows(IllegalStateException.class,()->service(mapping).receipt(p,"fixture-source","20001",c.requestId()));
    }
}
