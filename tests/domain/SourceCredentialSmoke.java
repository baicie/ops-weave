import com.acme.opsweave.integration.api.*;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class SourceCredentialSmoke {
 static int checks;static void check(boolean value){checks++;if(!value)throw new AssertionError("Credential "+checks);}
 static void failure(SourceCredentialFailure.Code code,Runnable r){checks++;try{r.run();}catch(SourceCredentialFailure e){if(e.code()==code)return;throw new AssertionError(e.code());}throw new AssertionError("Expected "+code);}
 static Principal user(String tenant,String owner){return new Principal(new SubjectId(owner),new TenantId(tenant),Set.of(Permission.SOURCE_SYNC,Permission.SOURCE_CONFIGURE),ResourceScope.tenantWide());}
 static SourceCredentialService.Write command(UUID request,int version,String name,String state,String secret){return new SourceCredentialService.Write(request,version,name,state,secret==null?null:secret.toCharArray());}
 static class Time extends Clock {Instant value=Instant.parse("2026-10-03T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}}
 public static void main(String[] args)throws Exception {
  var root=new byte[32];Arrays.fill(root,(byte)19);var crypt=new AesGcmCredentialProtector("fixture-key",Map.of("fixture-key",root));var store=new InMemoryWorkflowStore();var time=new Time();var p=user("credential-fixture","owner");var service=new SourceCredentialService(store,crypt,time);var id=UUID.randomUUID();var token="fixture-only-token-not-used-remotely";
  SourceCredential.Receipt created;try(var c=command(id,0,"Fixture credential","ACTIVE",token)){created=service.write(p,id,c,true);check(!c.toString().contains(token));check(service.write(p,id,c,true).equals(created));}
  var pin=created.credential().pin();check(created.credential().revision()==1);check(service.list(p).items().size()==1);check(service.list(p).canConfigure());check(service.list(p).vaultAvailable());check(service.versions(p,id).size()==1);check(!service.versions(p,id).getFirst().revoked());check(!created.toString().contains(token));
  AtomicReference<char[]> transientBytes=new AtomicReference<>();check(service.withSecret(p,pin,s->{transientBytes.set(s);return Arrays.equals(s,token.toCharArray());}));check(Arrays.equals(transientBytes.get(),new char[token.length()]));
  try(var c=command(id,0,"Fixture credential","ACTIVE","different-fixture-token")){failure(SourceCredentialFailure.Code.CONFLICT,()->service.write(p,id,c,true));}
  failure(SourceCredentialFailure.Code.NOT_FOUND,()->service.read(user("credential-fixture","other"),id));failure(SourceCredentialFailure.Code.NOT_FOUND,()->service.read(user("foreign-fixture","owner"),id));
  var syncOnly=new Principal(p.subjectId(),p.tenantId(),Set.of(Permission.SOURCE_SYNC),p.resourceScope());check(!service.list(syncOnly).canConfigure());try(var c=command(UUID.randomUUID(),1,"Renamed","ACTIVE",null)){failure(SourceCredentialFailure.Code.FORBIDDEN,()->service.write(syncOnly,id,c,false));}
  var denied=new Principal(p.subjectId(),p.tenantId(),Set.of(),p.resourceScope());failure(SourceCredentialFailure.Code.FORBIDDEN,()->service.read(denied,id));failure(SourceCredentialFailure.Code.FORBIDDEN,()->service.withSecret(denied,pin,s->true));
  var rotation=UUID.randomUUID();time.value=time.value.plusSeconds(1);try(var c=command(rotation,1,"Fixture rotated","ACTIVE","fixture-rotated-token")){var r=service.write(p,id,c,false);check(r.credential().revision()==2);check(r.credential().editVersion()==2);check(r.credential().versionId().equals(rotation));check(service.withSecret(p,r.credential().pin(),s->Arrays.equals(s,"fixture-rotated-token".toCharArray())));}
  check(service.withSecret(p,pin,s->Arrays.equals(s,token.toCharArray())));check(service.versions(p,id).size()==2);var revoked=UUID.randomUUID();var r=service.revoke(p,id,revoked,2,1);check(r.credential().editVersion()==3);check(service.revoke(p,id,revoked,2,1).equals(r));check(service.receipt(p,id,revoked).equals(r));failure(SourceCredentialFailure.Code.CONFLICT,()->service.withSecret(p,pin,s->true));check(service.versions(p,id).stream().filter(v->v.pin().revision()==1).findFirst().orElseThrow().revoked());
  var current=service.read(p,id);try(var c=command(UUID.randomUUID(),3,"Fixture renamed","ACTIVE",null)){var renamed=service.write(p,id,c,false);check(renamed.credential().revision()==2);check(renamed.credential().editVersion()==4);}
  var off=new SourceCredentialService(store,new AesGcmCredentialProtector(null,Map.of()),time);check(!off.list(p).vaultAvailable());var revokeCurrent=UUID.randomUUID();check(off.revoke(p,id,revokeCurrent,4,2).credential().editVersion()==5);failure(SourceCredentialFailure.Code.CONFLICT,()->off.withSecret(p,current.pin(),s->true));
  var id2=UUID.randomUUID();try(var c=command(id2,0,"Unavailable fixture","ACTIVE",token)){failure(SourceCredentialFailure.Code.UNAVAILABLE,()->off.write(p,id2,c,true));}check(service.list(p).items().size()==1);
  // Immutable ciphertext authenticates all scope fields and does not survive transplant or tampering.
  var scope=new CredentialProtector.Scope(p.tenantId(),p.subjectId().value(),pin);var sealed=crypt.seal(scope,token.toCharArray());var second=crypt.seal(scope,token.toCharArray());check(!sealed.nonce().equals(second.nonce()));check(!sealed.toString().contains(sealed.ciphertext()));check(Arrays.equals(crypt.open(scope,sealed),token.toCharArray()));
  failure(SourceCredentialFailure.Code.UNAVAILABLE,()->crypt.open(new CredentialProtector.Scope(new TenantId("foreign-fixture"),p.subjectId().value(),pin),sealed));failure(SourceCredentialFailure.Code.UNAVAILABLE,()->crypt.open(new CredentialProtector.Scope(p.tenantId(),"other",pin),sealed));failure(SourceCredentialFailure.Code.UNAVAILABLE,()->crypt.open(new CredentialProtector.Scope(p.tenantId(),p.subjectId().value(),new SourceCredential.Pin(pin.credentialId(),2,UUID.randomUUID())),sealed));
  byte[] bad=Base64.getDecoder().decode(sealed.ciphertext());bad[0]^=1;failure(SourceCredentialFailure.Code.UNAVAILABLE,()->crypt.open(scope,new SourceCredential.Envelope(sealed.keyId(),sealed.nonce(),Base64.getEncoder().encodeToString(bad))));
  byte[] nextRoot=new byte[32];Arrays.fill(nextRoot,(byte)27);var retained=new AesGcmCredentialProtector("fixture-next",Map.of("fixture-key",root,"fixture-next",nextRoot));check(Arrays.equals(retained.open(scope,sealed),token.toCharArray()));check(retained.seal(scope,token.toCharArray()).keyId().equals("fixture-next"));var removed=new AesGcmCredentialProtector("fixture-next",Map.of("fixture-next",nextRoot));failure(SourceCredentialFailure.Code.UNAVAILABLE,()->removed.open(scope,sealed));
  // A revocation during bounded IO prevents accepting the result and clears the temporary bytes.
  var id3=UUID.randomUUID();SourceCredential c3;try(var c=command(id3,0,"Fixture race","ACTIVE",token)){c3=service.write(p,id3,c,true).credential();}var holder=new AtomicReference<char[]>();failure(SourceCredentialFailure.Code.CONFLICT,()->service.withSecret(p,c3.pin(),s->{holder.set(s);service.revoke(p,id3,UUID.randomUUID(),1,1);return true;}));check(Arrays.equals(holder.get(),new char[token.length()]));
  // Same request key with a different owner is a collision, not a secret disclosure or overwrite.
  try(var c=command(id,0,"Other owner's fixture","ACTIVE",token)){failure(SourceCredentialFailure.Code.CONFLICT,()->service.write(user("credential-fixture","other"),id,c,true));}
  // Trusted key rotation retains the original HMAC key for exact replay; dropping it fails closed.
  var keyed=new SourceCredentialService(store,retained,time);try(var c=command(id,0,"Fixture credential","ACTIVE",token)){check(keyed.write(p,id,c,true).equals(created));}
  var withoutOldKey=new SourceCredentialService(store,removed,time);try(var c=command(id,0,"Fixture credential","ACTIVE",token)){failure(SourceCredentialFailure.Code.UNAVAILABLE,()->withoutOldKey.write(p,id,c,true));}
  var configureOnly=new Principal(p.subjectId(),p.tenantId(),Set.of(Permission.SOURCE_CONFIGURE),p.resourceScope());check(service.read(configureOnly,id).id().equals(id));failure(SourceCredentialFailure.Code.FORBIDDEN,()->service.withSecret(configureOnly,c3.pin(),s->true));
  var scoped=new Principal(p.subjectId(),p.tenantId(),p.permissions(),ResourceScope.of(Set.of(new ResourceRef(p.tenantId(),"credential","unrelated-fixture-id"))));failure(SourceCredentialFailure.Code.FORBIDDEN,()->service.read(scoped,id));
  // Per-owner capacity is bounded; failed appends keep previous pins and receipts intact.
  var bounded=new SourceCredentialService(new InMemoryWorkflowStore(),crypt,time);var one=UUID.randomUUID();try(var c=command(one,0,"Bounded Fixture","ACTIVE",token)){bounded.write(p,one,c,true);}
  for(int n=2;n<=100;n++)try(var c=command(UUID.randomUUID(),n-1,"Bounded Fixture","ACTIVE",token)){bounded.write(p,one,c,false);}
  try(var c=command(UUID.randomUUID(),100,"Bounded Fixture","ACTIVE",token)){failure(SourceCredentialFailure.Code.CAPACITY,()->bounded.write(p,one,c,false));}check(bounded.versions(p,one).size()==100);
  for(int n=101;n<=200;n++)try(var c=command(UUID.randomUUID(),n-1,"Bounded Fixture "+n,"ACTIVE",null)){bounded.write(p,one,c,false);}
  try(var c=command(UUID.randomUUID(),200,"Bounded Fixture changed","ACTIVE",null)){failure(SourceCredentialFailure.Code.CAPACITY,()->bounded.write(p,one,c,false));}check(bounded.read(p,one).editVersion()==200);
  var ownerLimit=new SourceCredentialService(new InMemoryWorkflowStore(),crypt,time);for(int n=0;n<100;n++){var newId=UUID.randomUUID();try(var c=command(newId,0,"Bounded Fixture "+n,"ACTIVE",token)){ownerLimit.write(p,newId,c,true);}}
  var extra=UUID.randomUUID();try(var c=command(extra,0,"Over capacity Fixture","ACTIVE",token)){failure(SourceCredentialFailure.Code.CAPACITY,()->ownerLimit.write(p,extra,c,true));}check(ownerLimit.list(p).items().size()==20);check(ownerLimit.list(p).truncated());
  var healthy=UUID.randomUUID();SourceCredential c4;try(var c=command(healthy,0,"Integrity Fixture","ACTIVE",token)){c4=service.write(p,healthy,c,true).credential();}
  var corruptRequest=UUID.randomUUID();store.transaction(p.tenantId(),s->{s.addCredentialReceipt(p.subjectId().value(),new SourceCredential.Receipt(corruptRequest,healthy,"EDIT","sha256:"+"a".repeat(64),null,null,new SourceCredential(healthy,c4.name(),1,UUID.randomUUID(),2,"ACTIVE",c4.createdAt(),c4.updatedAt())));return null;});failure(SourceCredentialFailure.Code.UNAVAILABLE,()->service.receipt(p,healthy,corruptRequest));
  var failedBytes=new AtomicReference<char[]>();try{service.withSecret(p,c4.pin(),s->{failedBytes.set(s);throw new IllegalStateException("Fixture connector failed");});throw new AssertionError();}catch(IllegalStateException expected){check(expected.getMessage().equals("Fixture connector failed"));}check(Arrays.equals(failedBytes.get(),new char[token.length()]));
  // The explicit memory adapter also rejects cross-tenant nonce reuse and rolls back the new metadata.
  var existingVersion=store.transaction(p.tenantId(),s->s.credentialVersions(p.subjectId().value(),healthy).getFirst());var otherTenant=new TenantId("nonce-collision-fixture");var otherId=UUID.randomUUID();try{store.transaction(otherTenant,s->{s.saveCredential("owner",new SourceCredential(otherId,"Nonce Fixture",1,otherId,1,"ACTIVE",time.instant(),time.instant()));s.addCredentialVersion("owner",new SourceCredential.Version(otherId,1,otherId,existingVersion.envelope(),time.instant()));return null;});throw new AssertionError();}catch(IllegalStateException expected){check(true);}check(store.transaction(otherTenant,s->s.credentials("owner").isEmpty()));
  System.out.println("Source credential smoke: "+checks+" checks passed");
 }
}
