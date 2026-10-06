package com.acme.opsweave.platform.workflow;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.integration.EnvSecretSource;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real JCA encryption; all identities, references and keys are synthetic fixtures. */
class AesWorkflowHistoryCursorsTest {
 final TenantId tenant=new TenantId("history-crypto-fixture");final String owner="history-author";
 final WorkflowQuality.Reference ref=new WorkflowQuality.Reference("history-fixture",1,"sha256:"+"a".repeat(64));
 final WorkflowHistory.Cursor cursor=new WorkflowHistory.Cursor("2.0",Instant.parse("2026-10-05T00:00:00.000001Z"),98765432101234L,45,20,UUID.randomUUID());
 byte[] material(){var bytes=new byte[32];Arrays.fill(bytes,(byte)42);return bytes;}
 void denied(Runnable run){assertEquals(WorkflowFailure.Code.NOT_FOUND,assertThrows(WorkflowFailure.class,run::run).code());}
 @Test void tokensReopenOnlyWithTheSameTrustedScopeAndKey(){
  var codec=new AesWorkflowHistoryCursors(material());var one=codec.seal(tenant,owner,ref,cursor);var two=codec.seal(tenant,owner,ref,cursor);assertNotEquals(one,two);assertTrue(one.matches("h1\\.[A-Za-z0-9_-]+"));assertTrue(one.length()<1024);assertEquals(cursor,codec.open(tenant,owner,ref,one));assertEquals(cursor,new AesWorkflowHistoryCursors(material()).open(tenant,owner,ref,one));assertFalse(one.contains("98765432101234"));assertFalse(new String(Base64.getUrlDecoder().decode(one.substring(3)),java.nio.charset.StandardCharsets.UTF_8).contains("history-author"));
  denied(()->codec.open(new TenantId("another-tenant"),owner,ref,one));denied(()->codec.open(tenant,"another-author",ref,one));denied(()->codec.open(tenant,owner,new WorkflowQuality.Reference(ref.id(),2,ref.digest()),one));denied(()->codec.open(tenant,owner,new WorkflowQuality.Reference(ref.id(),1,"sha256:"+"b".repeat(64)),one));var other=material();other[0]=0;denied(()->new AesWorkflowHistoryCursors(other).open(tenant,owner,ref,one));var bytes=Base64.getUrlDecoder().decode(one.substring(3));bytes[15]^=1;denied(()->codec.open(tenant,owner,ref,"h1."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)));denied(()->codec.open(tenant,owner,ref,"h1.abc"));for(var bad:List.of("h1.abc=",one+"=","h1."+"a".repeat(1025)))assertThrows(IllegalArgumentException.class,()->codec.open(tenant,owner,ref,bad));
 }
 @Test void productionMissingAndExplicitInvalidKeysNeverUseTheDevFallback(){
  assertThrows(IllegalArgumentException.class,()->new AesWorkflowHistoryCursors(new byte[31]));var secrets=new EnvSecretSource();assertThrows(IllegalStateException.class,()->AesWorkflowHistoryCursors.configured("",false,secrets).requireAvailable());assertThrows(IllegalStateException.class,()->AesWorkflowHistoryCursors.configured("inline:synthetic",true,secrets).requireAvailable());assertThrows(IllegalStateException.class,()->AesWorkflowHistoryCursors.configured("env:OPSWEAVE_SYNTHETIC_MISSING_CURSOR_135",true,secrets).requireAvailable());var dev=AesWorkflowHistoryCursors.configured("",true,secrets);dev.requireAvailable();var token=dev.seal(tenant,owner,ref,cursor);assertEquals(cursor,dev.open(tenant,owner,ref,token));denied(()->AesWorkflowHistoryCursors.configured("",true,secrets).open(tenant,owner,ref,token));
 }
}
