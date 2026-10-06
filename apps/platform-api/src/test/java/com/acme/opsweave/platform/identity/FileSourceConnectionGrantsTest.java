package com.acme.opsweave.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSourceConnectionGrantsTest {
 @TempDir Path directory;
 static final String ISSUER="https://identity.example.invalid",CREDENTIAL="11111111-1111-4111-8111-111111111111";
 Map<String,Object> grant(){return new LinkedHashMap<>(Map.of("issuer",ISSUER,"externalSubject","fixture-external-subject","subjectId","fixture-operator","tenantId","fixture-tenant","revision",1,"enabled",true,"permissions",List.of("source.sync","source.configure"),"scope",Map.of("tenantWide",false,"resources",List.of(Map.of("type","source-endpoint","id","fixture-host"),Map.of("type","credential","id",CREDENTIAL),Map.of("type","source","id","fixture-source"),Map.of("type","workflow","id","*")))));}
 Path file(Map<String,Object> row)throws Exception{return Files.writeString(directory.resolve("grants.json"),CatalogJson.JSON.writeValueAsString(Map.of("schemaVersion","1.0","grants",List.of(row))));}
 FileIdentityGrants reader(Path file){return new FileIdentityGrants(new OidcSettings(ISSUER,null,null,null,null,null,null,file.toString(),false,600));}
 @Test void scopedFileGrantAuthorizesOnlyNamedConnectionResourcesAndReloadsRevocation()throws Exception{
  var row=grant();var file=file(row);var grants=reader(file);var bound=grants.find("fixture-external-subject");var p=bound.grant().principal();var auth=new Authorizer();assertFalse(p.resourceScope().isTenantWide());assertEquals(4,p.resourceScope().allowedResources().size());
  for(var permission:List.of(Permission.SOURCE_SYNC,Permission.SOURCE_CONFIGURE)){assertTrue(auth.decide(p,new ResourceRef(p.tenantId(),"source-endpoint","fixture-host"),permission).allowed());assertTrue(auth.decide(p,new ResourceRef(p.tenantId(),"credential",CREDENTIAL),permission).allowed());assertTrue(auth.decide(p,ResourceRef.source(p.tenantId(),"fixture-source"),permission).allowed());assertTrue(auth.decide(p,new ResourceRef(p.tenantId(),"source-endpoint","other-fixture"),permission).denied());assertTrue(auth.decide(p,new ResourceRef(p.tenantId(),"credential",UUID.randomUUID().toString()),permission).denied());}
  assertTrue(auth.decide(p,new ResourceRef(p.tenantId(),"workflow","*"),Permission.SOURCE_SYNC).allowed());assertTrue(auth.decide(p,new ResourceRef(p.tenantId(),"workflow","*"),Permission.ENTITY_MANAGE).denied());assertFalse(p.has(Permission.ENTITY_MANAGE));row.put("enabled",false);row.put("revision",2);file(row);assertThrows(IllegalArgumentException.class,()->grants.find("fixture-external-subject"));
 }
 @Test void unknownResourceTypeCannotTurnOperatorMappingIntoAnHttpOrShellGrant()throws Exception{
  var row=grant();row.put("scope",Map.of("tenantWide",false,"resources",List.of(Map.of("type","arbitrary-http","id","fixture-host"))));var file=file(row);var e=assertThrows(IllegalStateException.class,()->reader(file));assertEquals("Identity authorization configuration unavailable",e.getMessage());assertNull(e.getCause());
 }
}
