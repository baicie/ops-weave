package com.acme.opsweave.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.AesGcmCredentialProtector;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual PostgreSQL transactions; addresses and credentials are explicit synthetic fixtures, with no IO. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresSourceConnectionIT extends OwnedInventoryTest {
 final TenantId tenant=new TenantId("connection-pg-fixture-"+UUID.randomUUID());final Principal p=new Principal(new SubjectId("fixture-owner"),tenant,Set.of(Permission.SOURCE_SYNC,Permission.SOURCE_CONFIGURE),ResourceScope.tenantWide());
 final DriverManagerDataSource db=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
 final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","zabbix-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
 final SourceEndpoint endpoint=SourceEndpoint.registered("fixture-host","Fixture address","https://192.0.2.10/api_jsonrpc.php");final Clock clock=Clock.systemUTC();
 SourceCredentialService credentials(){return new SourceCredentialService(new PostgresWorkflowStore(db),new AesGcmCredentialProtector("fixture-key",Map.of("fixture-key",new byte[32])),clock);}
 SourceConnectionService service(){return new SourceConnectionService(new PostgresWorkflowStore(db),new SourceEndpointService(new SourceEndpointCatalog(){public List<SourceEndpoint> list(TenantId t){return t.equals(tenant)?List.of(endpoint):List.of();}public Optional<SourceEndpoint> find(TenantId t,String id){return list(t).stream().filter(e->e.id().equals(id)).findFirst();}}),credentials(),clock);}
 SourceCredential.Pin pin(){var id=UUID.randomUUID();try(var c=new SourceCredentialService.Write(id,0,"Fixture encrypted credential","ACTIVE","fixture-value".toCharArray())){return credentials().write(p,id,c,true).credential().pin();}}
 SourceConnectionService.Write command(UUID key,int version,SourceCredential.Pin pin){return new SourceConnectionService.Write(key,version,"Fixture PG connection","Synthetic fixture",endpoint.pin(),pin,List.of("18"));}
 @Test void freshStoreKeepsCompleteHistoryAndOriginalReceiptAfterRotationAndRevocation(){var id=UUID.randomUUID();var pin=pin();var c=command(id,0,pin);var result=service().write(p,id,c,true);assertEquals(result,service().receipt(p,id,id));assertEquals(result,service().write(p,id,c,true));var rotate=UUID.randomUUID();SourceCredential.Pin next;try(var w=new SourceCredentialService.Write(rotate,1,"Fixture encrypted credential","ACTIVE","fixture-rotated".toCharArray())){next=credentials().write(p,pin.credentialId(),w,false).credential().pin();}var edited=service().write(p,id,command(UUID.randomUUID(),1,next),false);assertEquals(2,edited.connection().revision());assertEquals(2,service().history(p,id).size());credentials().revoke(p,pin.credentialId(),UUID.randomUUID(),2,2);assertEquals("UNAVAILABLE",service().read(p,id).availability());assertEquals(result,service().receipt(p,id,id));assertEquals(result,service().write(p,id,c,true));}
 @Test void concurrentBindingHasOneWinnerAndNoExtraConfiguration()throws Exception{var id=UUID.randomUUID();var pin=pin();service().write(p,id,command(id,0,pin),true);var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(3)){var futures=new ArrayList<Future<Boolean>>();for(int n=0;n<3;n++)futures.add(pool.submit(()->{start.await();try{service().write(p,id,command(UUID.randomUUID(),1,pin),false);return true;}catch(WorkflowFailure e){assertEquals(WorkflowFailure.Code.CONFLICT,e.code());return false;}}));start.countDown();int winners=0;for(var f:futures)if(f.get(15,TimeUnit.SECONDS))winners++;assertEquals(1,winners);}assertEquals(2,service().read(p,id).instance().editVersion());assertEquals(1,service().history(p,id).size());}
 @Test void failedTransactionRollsBackBothGenericAndCompleteSnapshots(){var pin=pin();var id=UUID.randomUUID();var snapshot=SourceConnectionConfiguration.snapshot(id,1,endpoint,pin,clock.instant());var store=new PostgresWorkflowStore(db);assertThrows(IllegalStateException.class,()->store.transaction(tenant,s->{s.addSourceConfiguration(p.subjectId().value(),new SourceInstance.Configuration(id,1,snapshot.connectionDigest(),"zabbix-jsonrpc",snapshot.createdAt()));s.addSourceConnection(p.subjectId().value(),snapshot);throw new IllegalStateException("Fixture rollback");}));assertTrue(store.transaction(tenant,s->s.sourceConnections(p.subjectId().value(),id)).isEmpty());assertTrue(store.transaction(tenant,s->s.sourceConfigurations(p.subjectId().value(),id)).isEmpty());}
 @Test void corruptSnapshotBodyCannotBecomeAvailable()throws Exception{var id=UUID.randomUUID();service().write(p,id,command(id,0,pin()),true);try(var c=db.getConnection();var q=c.prepareStatement("UPDATE integration.source_connection_configuration SET body=jsonb_set(body,'{credentialPin,revision}','2') WHERE tenant_id=? AND source_id=?")){q.setString(1,tenant.value());q.setObject(2,id);assertEquals(1,q.executeUpdate());}assertThrows(IllegalStateException.class,()->service().read(p,id));}
}
