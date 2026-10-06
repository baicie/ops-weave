package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.domain.SourceCredential;
import com.acme.opsweave.platform.catalog.CatalogJson;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

/** Public metadata and a separate closed storage format. Never expose the storage format to HTTP. */
public final class SourceCredentialJson {
    private SourceCredentialJson() {}
    public static Map<String,Object> wire(SourceCredential c) { return Map.of("id",c.id(),"name",c.name(),"revision",c.revision(),"versionId",c.versionId(),"editVersion",c.editVersion(),"state",c.state(),"createdAt",c.createdAt().toString(),"updatedAt",c.updatedAt().toString()); }
    public static SourceCredential metadata(String json) { return metadata(CatalogJson.JSON.readTree(json)); }
    private static UUID uuid(JsonNode n,String key) {var value=text(n,key);var id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}
    public static SourceCredential metadata(JsonNode n) {fields(n,Set.of("id","name","revision","versionId","editVersion","state","createdAt","updatedAt"));return new SourceCredential(uuid(n,"id"),text(n,"name"),integer(n,"revision",null),uuid(n,"versionId"),integer(n,"editVersion",null),text(n,"state"),Instant.parse(text(n,"createdAt")),Instant.parse(text(n,"updatedAt")));}
    public static Map<String,Object> publicReceipt(SourceCredential.Receipt r) { return Map.of("requestId",r.requestId(),"credentialId",r.credentialId(),"operation",r.operation(),"commandDigest",r.commandDigest(),"credential",wire(r.credential())); }
    public static Map<String,Object> storageReceipt(SourceCredential.Receipt r) {var n=new LinkedHashMap<>(publicReceipt(r));n.put("keyId",r.keyId());n.put("secretDigest",r.secretDigest());return n;}
    private static String nullable(JsonNode n,String key) {var v=n.get(key);if(v==null)throw new IllegalArgumentException();return v.isNull()?null:text(n,key);}
    public static SourceCredential.Receipt receipt(String json) {var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("requestId","credentialId","operation","commandDigest","credential","keyId","secretDigest"));return new SourceCredential.Receipt(uuid(n,"requestId"),uuid(n,"credentialId"),text(n,"operation"),text(n,"commandDigest"),nullable(n,"keyId"),nullable(n,"secretDigest"),metadata(n.get("credential")));}
    public static Map<String,Object> storageVersion(SourceCredential.Version v) { return Map.of("credentialId",v.credentialId(),"revision",v.revision(),"versionId",v.versionId(),"envelope",Map.of("keyId",v.envelope().keyId(),"nonce",v.envelope().nonce(),"ciphertext",v.envelope().ciphertext()),"createdAt",v.createdAt().toString()); }
    public static SourceCredential.Version version(String json) {var n=CatalogJson.JSON.readTree(json);fields(n,Set.of("credentialId","revision","versionId","envelope","createdAt"));var e=n.get("envelope");fields(e,Set.of("keyId","nonce","ciphertext"));return new SourceCredential.Version(uuid(n,"credentialId"),integer(n,"revision",null),uuid(n,"versionId"),new SourceCredential.Envelope(text(e,"keyId"),text(e,"nonce"),text(e,"ciphertext")),Instant.parse(text(n,"createdAt")));}
}
