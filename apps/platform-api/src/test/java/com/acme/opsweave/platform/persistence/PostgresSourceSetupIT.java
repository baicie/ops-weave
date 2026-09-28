package com.acme.opsweave.platform.persistence;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.catalog.BuiltinCatalog;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.api.WorkflowStore.*;
import com.acme.opsweave.integration.application.SourceSetupService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresSourceSetupIT extends OwnedInventoryTest {
 final TenantId tenant=new TenantId("workflow-pg-"+UUID.randomUUID());
 final DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
 final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
 final com.acme.opsweave.catalog.domain.ModelDefinition model=new BuiltinCatalog().definitions().stream().filter(m->m.id().equals("builtin.service")).findFirst().orElseThrow();
 Principal user(String owner){return new Principal(new SubjectId(owner),tenant,Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());}

 static final String HASH="sha256:"+"a".repeat(64);
 SourceSetupService service(){return new SourceSetupService(new PostgresWorkflowStore(source),(p,t)->model,(p,s)->new SourceSetupService.Connection(HASH,"MANUAL_SAMPLE"),Clock.systemUTC());}
 SourceSetupService.Command command(){return new SourceSetupService.Command(UUID.randomUUID(),"PG fixture setup","Fixture",new Source("MANUAL_SAMPLE","manual"),HASH,new Target(model.id(),1,model.digest()));}
 @Test void reopenAndIsolation(){var p=user("one");var c=command();var confirmed=service().confirm(p,c);assertEquals(confirmed,service().read(p,c.requestId()));assertEquals(confirmed,service().confirm(p,c));assertEquals(1,service().list(p).items().size());assertTrue(service().list(user("two")).items().isEmpty());var other=new Principal(p.subjectId(),new TenantId(tenant.value()+"-other"),p.permissions(),p.resourceScope());assertTrue(service().list(other).items().isEmpty());assertThrows(WorkflowFailure.class,()->service().read(user("two"),c.requestId()));}
 @Test void concurrentConfirmCreatesOneSetupAndOneDraft()throws Exception{var c=command();var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(4)){var futures=new ArrayList<Future<SourceSetupService.Confirmed>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{start.await();return service().confirm(user("one"),c);}));start.countDown();var first=futures.getFirst().get(15,TimeUnit.SECONDS);for(var f:futures)assertEquals(first,f.get(15,TimeUnit.SECONDS));}assertEquals(1,service().list(user("one")).items().size());assertEquals(1,new PostgresWorkflowStore(source).transaction(tenant,s->s.drafts("one").size()).intValue());}
 @Test void transactionFailureRollsBackSetupAndDraft(){var c=command();var confirmed=service().confirm(user("one"),c);var store=new PostgresWorkflowStore(source);assertThrows(IllegalStateException.class,()->store.transaction(tenant,s->{s.saveSetup("two",confirmed.setup());s.saveDraft("two",confirmed.workflow());throw new IllegalStateException("Fixture rollback");}));assertTrue(service().list(user("two")).items().isEmpty());assertTrue(store.transaction(tenant,s->s.drafts("two").isEmpty()).booleanValue());}
}
