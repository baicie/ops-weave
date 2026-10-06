package com.acme.opsweave.platform.workflow;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.integration.api.CredentialProtector;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class SourceCredentialVaultTest {
 @TempDir Path dir;
 String root=Base64.getEncoder().encodeToString(new byte[32]);
 Path file(String text)throws Exception{return Files.writeString(dir.resolve(UUID.randomUUID()+".json"),text);}
 @Test void absentKeyringIsExplicitlyUnavailableAndValidFileSurvivesRestart()throws Exception{
  assertFalse(new SourceCredentialVault("").available());var path=file("{\"activeKeyId\":\"fixture-key\",\"keys\":{\"fixture-key\":\""+root+"\"}}");var a=new SourceCredentialVault(path.toString());assertTrue(a.available());
  var scope=new CredentialProtector.Scope(new TenantId("vault-fixture"),"owner",new SourceCredential.Pin(UUID.randomUUID(),1,UUID.randomUUID()));var secret="fixture-only-secret".toCharArray();var envelope=a.seal(scope,secret);assertArrayEquals(secret,new SourceCredentialVault(path.toString()).open(scope,envelope));assertFalse(a.toString().contains(root));
 }
 @Test void invalidKeyringNeverFallsBackAndErrorsDoNotEchoFileOrKeys()throws Exception{
  for(String text:List.of("{}","{\"activeKeyId\":\"missing\",\"keys\":{\"fixture-key\":\""+root+"\"}}","{\"activeKeyId\":\"fixture-key\",\"keys\":{\"fixture-key\":\"bad-fixture-root\"}}","{\"activeKeyId\":\"fixture-key\",\"keys\":{\"fixture-key\":\""+root+"\"},\"url\":\"https://fixture.invalid\"}","{\"activeKeyId\":\"fixture-key\",\"activeKeyId\":\"fixture-key\",\"keys\":{}}","{} {}","x".repeat(8193))){var path=file(text);var e=assertThrows(IllegalStateException.class,()->new SourceCredentialVault(path.toString()));assertEquals("Credential keyring configuration is invalid",e.getMessage());assertNull(e.getCause());}
  assertThrows(IllegalStateException.class,()->new SourceCredentialVault("relative-keyring.json"));Files.createDirectory(dir.resolve(".git"));assertThrows(IllegalStateException.class,()->new SourceCredentialVault(file("{}").toString()));
 }
 @Test void plaintextRequiresTlsOrExplicitTwoSidedLoopbackDevBoundary(){
  var dev=new OpsweaveProperties(new OpsweaveProperties.Auth("dev",true,null),null,null);var closed=new OpsweaveProperties(new OpsweaveProperties.Auth("closed",true,null),null,null);var r=new MockHttpServletRequest();r.setLocalAddr("127.0.0.1");r.setRemoteAddr("127.0.0.1");assertTrue(SourceCredentialController.secretTransport(r,dev));assertFalse(SourceCredentialController.secretTransport(r,closed));
  r.setRemoteAddr("192.0.2.1");r.addHeader("X-Forwarded-Proto","https");assertFalse(SourceCredentialController.secretTransport(r,dev));r.setSecure(true);assertTrue(SourceCredentialController.secretTransport(r,closed));r.setSecure(false);r.setRemoteAddr("::1");r.setLocalAddr("192.0.2.2");assertFalse(SourceCredentialController.secretTransport(r,dev));
 }
}
