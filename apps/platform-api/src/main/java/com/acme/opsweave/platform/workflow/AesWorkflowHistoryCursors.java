package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.api.WorkflowHistoryCursors;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowHistory.Cursor;
import com.acme.opsweave.integration.domain.WorkflowQuality.Reference;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.integration.EnvSecretSource;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

/** Deployment-owned AES-GCM key. Tokens hide insertion counters and bind trusted scope as AAD. */
public final class AesWorkflowHistoryCursors implements WorkflowHistoryCursors {
    private final SecretKeySpec key;private final SecureRandom random=new SecureRandom();
    public AesWorkflowHistoryCursors(byte[] material){if(material==null||material.length!=32)throw new IllegalArgumentException("Invalid history cursor key");key=new SecretKeySpec(material,"AES");}
    public static WorkflowHistoryCursors configured(String reference,boolean localDev,EnvSecretSource secrets){
        byte[] material=null;
        try{
            if(reference.isEmpty()){if(!localDev)return unavailable();material=new byte[32];new SecureRandom().nextBytes(material);}
            else{var value=secrets.resolve(reference);material=Base64.getDecoder().decode(value);if(material.length!=32||!Base64.getEncoder().encodeToString(material).equals(value))return unavailable();}
            return new AesWorkflowHistoryCursors(material);
        }catch(RuntimeException invalid){return unavailable();}finally{if(material!=null)Arrays.fill(material,(byte)0);}
    }
    private static WorkflowHistoryCursors unavailable(){return new WorkflowHistoryCursors(){public void requireAvailable(){throw new IllegalStateException("History cursor key unavailable");}public String seal(TenantId t,String o,Reference r,Cursor c){requireAvailable();throw new AssertionError();}public Cursor open(TenantId t,String o,Reference r,String token){requireAvailable();throw new AssertionError();}};}
    public void requireAvailable() {}
    private static byte[] aad(TenantId tenant,String owner,Reference ref){return WorkflowDefinition.hash(List.of("workflow-history-cursor-v1",tenant.value(),owner,ref.id(),Integer.toString(ref.revision()),ref.digest())).getBytes(StandardCharsets.US_ASCII);}
    public String seal(TenantId tenant,String owner,Reference ref,Cursor cursor){
        byte[] nonce=new byte[12];random.nextBytes(nonce);
        try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(tenant,owner,ref));var body=cipher.doFinal(CatalogJson.JSON.writeValueAsBytes(cursor));byte[] token=new byte[nonce.length+body.length];System.arraycopy(nonce,0,token,0,nonce.length);System.arraycopy(body,0,token,nonce.length,body.length);return "h1."+Base64.getUrlEncoder().withoutPadding().encodeToString(token);}catch(GeneralSecurityException invalid){throw new IllegalStateException("History cursor unavailable");}
    }
    public Cursor open(TenantId tenant,String owner,Reference ref,String token){
        if(token==null||token.length()>1024||!token.matches("h1\\.[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
        byte[] plain=null;
        try{
            var encoded=token.substring(3);var body=Base64.getUrlDecoder().decode(encoded);if(body.length<29||body.length>768||!Base64.getUrlEncoder().withoutPadding().encodeToString(body).equals(encoded))throw new IllegalArgumentException();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(body,12)));cipher.updateAAD(aad(tenant,owner,ref));plain=cipher.doFinal(Arrays.copyOfRange(body,12,body.length));if(plain.length>512)throw new IllegalArgumentException();
            var n=CatalogJson.JSON.readTree(plain);fields(n,Set.of("schemaVersion","snapshotAt","watermark","recordedCount","seen","lastId"));if(n.size()!=6)throw new IllegalArgumentException();var w=n.get("watermark");if(w==null||!w.isIntegralNumber()||!w.canConvertToLong())throw new IllegalArgumentException();
            return new Cursor(text(n,"schemaVersion"),WorkflowSampleRecoveryJson.instant(text(n,"snapshotAt")),w.longValue(),integer(n,"recordedCount",null),integer(n,"seen",null),WorkflowRecoveryJson.canonical(text(n,"lastId")));
        }catch(GeneralSecurityException|RuntimeException invalid){throw new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND);}finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
}
