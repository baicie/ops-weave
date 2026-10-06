package com.acme.opsweave.platform.persistence;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.integration.application.SourceCredentialService;
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

/** Real PostgreSQL; all credential values and roots are explicit synthetic fixtures. */
@EnabledIfEnvironmentVariable(named="OPSWEAVE_TEST_JDBC_URL",matches=".+")
class PostgresSourceCredentialIT extends OwnedInventoryTest {
 final TenantId tenant=new TenantId("credential-pg-fixture-"+UUID.randomUUID());final Principal p=new Principal(new SubjectId("credential-fixture-owner"),tenant,Set.of(Permission.SOURCE_SYNC,Permission.SOURCE_CONFIGURE),ResourceScope.tenantWide());
 final DriverManagerDataSource source=new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
 final InventoryWiring wiring=openInventory(new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,new OpsweaveProperties.Auth.Dev("","",tenant.value(),"","")),new OpsweaveProperties.Zabbix("fixture","","env:OPSWEAVE_ZABBIX_TOKEN","fixture-1",1),new OpsweaveProperties.Inventory("postgres",System.getenv("OPSWEAVE_TEST_JDBC_URL"),System.getenv("OPSWEAVE_TEST_JDBC_USER"),System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"))));
 SourceCredentialService service(){return new SourceCredentialService(new PostgresWorkflowStore(source),new AesGcmCredentialProtector("fixture-key",Map.of("fixture-key",new byte[32])),Clock.systemUTC());}
 SourceCredential.Receipt write(UUID id,UUID request,int expected,String name,String secret,boolean create){try(var c=new SourceCredentialService.Write(request,expected,name,"ACTIVE",secret==null?null:secret.toCharArray())){return service().write(p,id,c,create);}}
 @Test void restartPreservesCiphertextOriginalReceiptsAndFixedVersions()throws Exception{
  var id=UUID.randomUUID();var original=write(id,id,0,"PG Fixture credential","fixture-secret-one",true);assertEquals(original,write(id,id,0,"PG Fixture credential","fixture-secret-one",true));var rotated=write(id,UUID.randomUUID(),1,"PG Fixture credential","fixture-secret-two",false);assertEquals(2,rotated.credential().revision());assertEquals(original,service().receipt(p,id,id));assertTrue(service().withSecret(p,original.credential().pin(),s->Arrays.equals(s,"fixture-secret-one".toCharArray())).booleanValue());assertEquals(2,service().versions(p,id).size());
  try(var c=source.getConnection();var q=c.prepareStatement("SELECT body::text FROM integration.source_credential_version WHERE tenant_id=? AND credential_id=?")){q.setString(1,tenant.value());q.setObject(2,id);try(var rows=q.executeQuery()){int count=0;while(rows.next()){assertFalse(rows.getString(1).contains("fixture-secret"));count++;}assertEquals(2,count);}}
  var revoked=service().revoke(p,id,UUID.randomUUID(),2,1);assertEquals(3,revoked.credential().editVersion());assertThrows(SourceCredentialFailure.class,()->service().withSecret(p,original.credential().pin(),s->true));assertTrue(service().withSecret(p,rotated.credential().pin(),s->Arrays.equals(s,"fixture-secret-two".toCharArray())).booleanValue());
  var other=new Principal(new SubjectId("other-fixture-owner"),tenant,p.permissions(),p.resourceScope());assertThrows(SourceCredentialFailure.class,()->service().read(other,id));assertTrue(service().list(other).items().isEmpty());var foreign=new Principal(p.subjectId(),new TenantId(tenant.value()+"-foreign"),p.permissions(),p.resourceScope());assertThrows(SourceCredentialFailure.class,()->service().read(foreign,id));
 }
 @Test void concurrentCasAppendsExactlyOneVersionAndReceipt()throws Exception{
  var id=UUID.randomUUID();write(id,id,0,"PG race Fixture","fixture-original",true);var start=new CountDownLatch(1);int winners=0;
  try(var pool=Executors.newFixedThreadPool(4)){var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<4;i++){int n=i;tasks.add(pool.submit(()->{start.await();try{write(id,UUID.randomUUID(),1,"PG race Fixture","fixture-rotation-"+n,false);return true;}catch(SourceCredentialFailure e){assertEquals(SourceCredentialFailure.Code.CONFLICT,e.code());return false;}}));}start.countDown();for(var f:tasks)if(f.get(15,TimeUnit.SECONDS))winners++;}
  assertEquals(1,winners);assertEquals(2,service().read(p,id).revision());assertEquals(2,service().versions(p,id).size());assertEquals(2,new PostgresWorkflowStore(source).transaction(tenant,s->s.credentialReceiptCount(p.subjectId().value())).intValue());
 }
 @Test void nonceCollisionAndTransactionFailureRollbackMetadata() {
  var id=UUID.randomUUID();var receipt=write(id,id,0,"PG rollback Fixture","fixture-secret",true);var store=new PostgresWorkflowStore(source);var version=store.transaction(tenant,s->s.credentialVersions(p.subjectId().value(),id).getFirst());var nextId=UUID.randomUUID();var time=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  assertThrows(IllegalStateException.class,()->store.transaction(tenant,s->{s.saveCredential(p.subjectId().value(),new SourceCredential(id,receipt.credential().name(),2,nextId,2,"ACTIVE",receipt.credential().createdAt(),time));s.addCredentialVersion(p.subjectId().value(),new SourceCredential.Version(id,2,nextId,version.envelope(),time));return null;}));assertEquals(receipt.credential(),service().read(p,id));assertEquals(1,service().versions(p,id).size());
  assertThrows(IllegalStateException.class,()->store.transaction(tenant,s->{s.saveCredential(p.subjectId().value(),new SourceCredential(id,"Changed Fixture",1,id,2,"ACTIVE",receipt.credential().createdAt(),time));throw new IllegalStateException("Fixture rollback");}));assertEquals(receipt.credential(),service().read(p,id));
 }
 @Test void corruptedCiphertextOrIndexedMetadataFailsClosed()throws Exception{
  var id=UUID.randomUUID();var receipt=write(id,id,0,"PG corrupt Fixture","fixture-secret",true);
  try(var c=source.getConnection();var q=c.prepareStatement("UPDATE integration.source_credential_version SET body=jsonb_set(body,'{envelope,ciphertext}',to_jsonb(?::text)) WHERE tenant_id=? AND credential_id=?")){q.setString(1,Base64.getEncoder().encodeToString(new byte[30]));q.setString(2,tenant.value());q.setObject(3,id);assertEquals(1,q.executeUpdate());}assertThrows(SourceCredentialFailure.class,()->service().withSecret(p,receipt.credential().pin(),s->true));
  try(var c=source.getConnection();var q=c.prepareStatement("UPDATE integration.source_credential SET body=jsonb_set(body,'{id}',to_jsonb(?::text)) WHERE tenant_id=? AND credential_id=?")){q.setString(1,UUID.randomUUID().toString());q.setString(2,tenant.value());q.setObject(3,id);assertEquals(1,q.executeUpdate());}assertThrows(IllegalStateException.class,()->service().read(p,id));
 }
}
