import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class SourceEndpointSmoke {
 static int checks;static void check(boolean value){checks++;if(!value)throw new AssertionError("Endpoint "+checks);}
 static void fails(WorkflowFailure.Code code,Runnable r){checks++;try{r.run();}catch(WorkflowFailure e){if(e.code()==code)return;throw new AssertionError(e.code());}throw new AssertionError("Expected "+code);}
 static void credentialFails(SourceCredentialFailure.Code code,Runnable r){checks++;try{r.run();}catch(SourceCredentialFailure e){if(e.code()==code)return;throw new AssertionError(e.code());}throw new AssertionError("Expected "+code);}
 static Principal principal(String tenant,String owner,Set<Permission> permissions,ResourceScope scope){return new Principal(new SubjectId(owner),new TenantId(tenant),permissions,scope);}
 static final class Catalog implements SourceEndpointCatalog {
  Map<String,SourceEndpoint> entries=new HashMap<>();TenantId tenant=new TenantId("endpoint-fixture");
  public List<SourceEndpoint> list(TenantId t){return t.equals(tenant)?List.copyOf(entries.values()):List.of();}
  public Optional<SourceEndpoint> find(TenantId t,String id){return t.equals(tenant)?Optional.ofNullable(entries.get(id)):Optional.empty();}
 }
 static void invalid(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected invalid endpoint");}
 public static void main(String[] args){
  var p=principal("endpoint-fixture","owner",Set.of(Permission.SOURCE_SYNC,Permission.SOURCE_CONFIGURE),ResourceScope.tenantWide());
  var endpoint=SourceEndpoint.registered("fixture-first","Fixture endpoint","https://192.0.2.10/api_jsonrpc.php");var second=SourceEndpoint.registered("fixture-second","Second Fixture","https://192.0.2.11/api_jsonrpc.php");var cat=new Catalog();cat.entries.put(endpoint.id(),endpoint);cat.entries.put(second.id(),second);var service=new SourceEndpointService(cat);
  check(service.list(p).size()==2);check(service.read(p,endpoint.id()).equals(endpoint));check(service.requireReadPin(p,endpoint.pin()).equals(endpoint));
  check(SourceEndpoint.registered(endpoint.id(),"Renamed Fixture",endpoint.address()).digest().equals(endpoint.digest()));check(!SourceEndpoint.registered(endpoint.id(),endpoint.name(),second.address()).digest().equals(endpoint.digest()));
  invalid(()->SourceEndpoint.registered("../file","Fixture",endpoint.address()));invalid(()->SourceEndpoint.registered("ok","name\n",endpoint.address()));invalid(()->new SourceEndpoint(endpoint.id(),endpoint.name(),endpoint.connectorKind(),endpoint.address(),"sha256:"+"0".repeat(64)));
  var denied=principal("endpoint-fixture","owner",Set.of(),p.resourceScope());fails(WorkflowFailure.Code.FORBIDDEN,()->service.list(denied));fails(WorkflowFailure.Code.FORBIDDEN,()->service.read(denied,endpoint.id()));
  var scoped=principal("endpoint-fixture","owner",p.permissions(),ResourceScope.of(Set.of(new ResourceRef(p.tenantId(),"source-endpoint",endpoint.id()))));check(service.list(scoped).equals(List.of(endpoint)));fails(WorkflowFailure.Code.FORBIDDEN,()->service.read(scoped,second.id()));
  var foreign=principal("endpoint-foreign-fixture","owner",p.permissions(),p.resourceScope());check(service.list(foreign).isEmpty());fails(WorkflowFailure.Code.NOT_FOUND,()->service.read(foreign,endpoint.id()));
  var configureOnly=principal("endpoint-fixture","owner",Set.of(Permission.SOURCE_CONFIGURE),p.resourceScope());check(service.read(configureOnly,endpoint.id()).equals(endpoint));fails(WorkflowFailure.Code.FORBIDDEN,()->service.requireReadPin(configureOnly,endpoint.pin()));fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->service.requireReadPin(p,new SourceEndpoint.Pin(endpoint.id(),second.digest())));
  var broken=new SourceEndpointService(new SourceEndpointCatalog(){public List<SourceEndpoint> list(TenantId t){return List.of(endpoint,endpoint);}public Optional<SourceEndpoint> find(TenantId t,String id){return Optional.of(second);}});
  try{broken.list(p);throw new AssertionError();}catch(IllegalStateException expected){check(true);}try{broken.read(p,endpoint.id());throw new AssertionError();}catch(IllegalStateException expected){check(true);}
  var store=new InMemoryWorkflowStore();var credentials=new SourceCredentialService(store,new AesGcmCredentialProtector("fixture-key",Map.of("fixture-key",new byte[32])),Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"),ZoneOffset.UTC));var id=UUID.randomUUID();SourceCredential c;
  try(var write=new SourceCredentialService.Write(id,0,"Fixture secret","ACTIVE","fixture-original-token".toCharArray())){c=credentials.write(p,id,write,true).credential();}
  var reads=new SourceEndpointReadService(service,credentials);var count=new AtomicInteger();var bytes=new AtomicReference<char[]>();
  check(reads.read(p,endpoint.pin(),c.pin(),(e,s)->{count.incrementAndGet();bytes.set(s);return e.equals(endpoint)&&Arrays.equals(s,"fixture-original-token".toCharArray());}));check(count.get()==1);check(Arrays.equals(bytes.get(),new char[bytes.get().length]));
  var rotation=UUID.randomUUID();try(var write=new SourceCredentialService.Write(rotation,1,"Fixture secret","ACTIVE","fixture-rotated-token".toCharArray())){credentials.write(p,id,write,false);}
  check(reads.read(p,endpoint.pin(),c.pin(),(e,s)->Arrays.equals(s,"fixture-original-token".toCharArray())));
  credentialFails(SourceCredentialFailure.Code.NOT_FOUND,()->reads.read(principal("endpoint-fixture","other",p.permissions(),p.resourceScope()),endpoint.pin(),c.pin(),(e,s)->{count.incrementAndGet();return true;}));check(count.get()==1);
  credentialFails(SourceCredentialFailure.Code.NOT_FOUND,()->reads.read(p,endpoint.pin(),new SourceCredential.Pin(id,2,UUID.randomUUID()),(e,s)->{count.incrementAndGet();return true;}));check(count.get()==1);
  fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->reads.read(p,new SourceEndpoint.Pin(endpoint.id(),second.digest()),c.pin(),(e,s)->{count.incrementAndGet();return true;}));check(count.get()==1);
  fails(WorkflowFailure.Code.SOURCE_UNAVAILABLE,()->reads.read(p,endpoint.pin(),c.pin(),(e,s)->{bytes.set(s);cat.entries.put(endpoint.id(),SourceEndpoint.registered(endpoint.id(),endpoint.name(),second.address()));return true;}));check(Arrays.equals(bytes.get(),new char[bytes.get().length]));
  cat.entries.put(endpoint.id(),endpoint);check(reads.read(p,endpoint.pin(),c.pin(),(e,s)->{cat.entries.put(endpoint.id(),SourceEndpoint.registered(endpoint.id(),"Changed label",endpoint.address()));return true;}));
  try{reads.read(p,endpoint.pin(),c.pin(),(e,s)->{bytes.set(s);throw new IllegalStateException("Fixture transport failure");});throw new AssertionError();}catch(IllegalStateException expected){check(expected.getMessage().equals("Fixture transport failure"));}check(Arrays.equals(bytes.get(),new char[bytes.get().length]));
  credentialFails(SourceCredentialFailure.Code.CONFLICT,()->reads.read(p,endpoint.pin(),c.pin(),(e,s)->{bytes.set(s);credentials.revoke(p,id,UUID.randomUUID(),2,1);return true;}));check(Arrays.equals(bytes.get(),new char[bytes.get().length]));
  credentialFails(SourceCredentialFailure.Code.CONFLICT,()->reads.read(p,endpoint.pin(),c.pin(),(e,s)->{count.incrementAndGet();return true;}));check(count.get()==1);
  fails(WorkflowFailure.Code.FORBIDDEN,()->reads.read(configureOnly,endpoint.pin(),c.pin(),(e,s)->{count.incrementAndGet();return true;}));check(count.get()==1);
  System.out.println("Source endpoint smoke: "+checks+" checks passed");
 }
}
