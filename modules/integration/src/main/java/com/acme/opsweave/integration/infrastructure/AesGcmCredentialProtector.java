package com.acme.opsweave.integration.infrastructure;

import com.acme.opsweave.integration.api.CredentialProtector;
import com.acme.opsweave.integration.application.SourceCredentialService;
import com.acme.opsweave.integration.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** JCA adapter with distinct encryption/authentication keys and object-bound authenticated data. */
public final class AesGcmCredentialProtector implements CredentialProtector {
    private record Keys(SecretKeySpec encryption, SecretKeySpec authentication) {}
    private final String active; private final Map<String,Keys> keys; private final SecureRandom random=new SecureRandom();
    public AesGcmCredentialProtector(String activeKeyId,Map<String,byte[]> roots) {
        if(roots.isEmpty()) { if(activeKeyId!=null)throw new IllegalArgumentException("Invalid credential keyring");active=null;keys=Map.of();return; }
        if(roots.size()>8 || activeKeyId==null || !roots.containsKey(activeKeyId))throw new IllegalArgumentException("Invalid credential keyring");
        var derived=new HashMap<String,Keys>();
        roots.forEach((id,value)->{if(id==null||!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")||value==null||value.length!=32)throw new IllegalArgumentException("Invalid credential keyring");
            byte[] encryption=derive(value,"opsweave-source-credential-encryption-v1:"+id),authentication=derive(value,"opsweave-source-credential-command-v1:"+id);
            try { derived.put(id,new Keys(new SecretKeySpec(encryption,"AES"),new SecretKeySpec(authentication,"HmacSHA256"))); } finally { Arrays.fill(encryption,(byte)0);Arrays.fill(authentication,(byte)0); }
        });active=activeKeyId;keys=Map.copyOf(derived);
    }
    private static SourceCredentialFailure unavailable() { return new SourceCredentialFailure(SourceCredentialFailure.Code.UNAVAILABLE); }
    private Keys key(String id) { var k=keys.get(id);if(k==null)throw unavailable();return k; }
    private static byte[] derive(byte[] root,String label) { try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(root,"HmacSHA256"));return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));} catch(GeneralSecurityException failure){throw unavailable();} }
    @Override public boolean available() { return active!=null; }
    @Override public boolean canOpen(String keyId){return keys.containsKey(keyId);}
    @Override public String activeKeyId() { if(active==null)throw unavailable();return active; }
    private static byte[] bytes(char[] secret) { SourceCredentialService.validateSecret(secret);var bytes=new byte[secret.length];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)secret[i];return bytes; }
    private static List<String> aad(Scope scope,String key) { var p=scope.pin();return List.of("source-credential-envelope-v1",scope.tenant().value(),scope.owner(),p.credentialId().toString(),Integer.toString(p.revision()),p.versionId().toString(),key); }
    private static byte[] encoded(List<String> parts) {var out=new java.io.ByteArrayOutputStream();for(var part:parts){var bytes=part.getBytes(StandardCharsets.UTF_8);out.writeBytes((bytes.length+":").getBytes(StandardCharsets.US_ASCII));out.writeBytes(bytes);}return out.toByteArray();}
    @Override public SourceCredential.Envelope seal(Scope scope,char[] secret) {
        var id=activeKeyId();var nonce=new byte[12];random.nextBytes(nonce);var plaintext=bytes(secret);
        try {var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(id).encryption(),new GCMParameterSpec(128,nonce));cipher.updateAAD(encoded(aad(scope,id)));var encrypted=cipher.doFinal(plaintext);return new SourceCredential.Envelope(id,Base64.getEncoder().encodeToString(nonce),Base64.getEncoder().encodeToString(encrypted));}
        catch(GeneralSecurityException failure){throw unavailable();}finally{Arrays.fill(plaintext,(byte)0);}
    }
    @Override public char[] open(Scope scope,SourceCredential.Envelope envelope) {
        byte[] plaintext=null;
        try {var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(envelope.keyId()).encryption(),new GCMParameterSpec(128,Base64.getDecoder().decode(envelope.nonce())));cipher.updateAAD(encoded(aad(scope,envelope.keyId())));plaintext=cipher.doFinal(Base64.getDecoder().decode(envelope.ciphertext()));var secret=new char[plaintext.length];for(int i=0;i<secret.length;i++)secret[i]=(char)(plaintext[i]&255);try {SourceCredentialService.validateSecret(secret);return secret;}catch(IllegalArgumentException invalid){Arrays.fill(secret,'\0');throw unavailable();}}
        catch(GeneralSecurityException|IllegalArgumentException failure){throw unavailable();}finally{if(plaintext!=null)Arrays.fill(plaintext,(byte)0);}
    }
    @Override public String authenticate(String keyId,List<String> commandParts,char[] secret) {
        var plaintext=bytes(secret);
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(key(keyId).authentication());mac.update(encoded(commandParts));mac.update((plaintext.length+":").getBytes(StandardCharsets.US_ASCII));return "hmac-sha256:"+HexFormat.of().formatHex(mac.doFinal(plaintext));}
        catch(GeneralSecurityException failure){throw unavailable();}finally{Arrays.fill(plaintext,(byte)0);}
    }
    @Override public String toString(){return "CredentialProtector[redacted]";}
}
