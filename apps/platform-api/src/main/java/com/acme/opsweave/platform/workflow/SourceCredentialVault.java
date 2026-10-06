package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.integration.api.CredentialProtector;
import com.acme.opsweave.integration.domain.SourceCredential;
import com.acme.opsweave.integration.infrastructure.AesGcmCredentialProtector;
import com.acme.opsweave.platform.catalog.CatalogJson;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

/** Deployment-controlled keyring. No request input, download, generated key or fallback. */
@Component
public final class SourceCredentialVault implements CredentialProtector {
    private final CredentialProtector delegate;
    public SourceCredentialVault(@Value("${opsweave.credentials.keyring-path:}") String file) {
        if(file==null||file.isBlank()){delegate=new AesGcmCredentialProtector(null,Map.of());return;}
        var roots=new HashMap<String,byte[]>();
        try { var configured=Path.of(file);if(!configured.isAbsolute())throw new IllegalArgumentException();var path=configured.toRealPath();
            for(var parent=path.getParent();parent!=null;parent=parent.getParent())if(Files.exists(parent.resolve(".git")))throw new IllegalArgumentException();
            if(!Files.isRegularFile(path))throw new IllegalArgumentException();byte[] bytes;
            try(var stream=Files.newInputStream(path)){bytes=stream.readNBytes(8193);}try{if(bytes.length==0||bytes.length>8192)throw new IllegalArgumentException();var n=CatalogJson.JSON.readTree(bytes);fields(n,Set.of("activeKeyId","keys"));var keys=n.get("keys");if(keys==null||!keys.isObject()||keys.isEmpty()||keys.size()>8)throw new IllegalArgumentException();
                for(var id:keys.propertyNames()){var v=keys.get(id);if(!v.isString()||!id.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))throw new IllegalArgumentException();var root=Base64.getDecoder().decode(v.asText());roots.put(id,root);if(root.length!=32||!Base64.getEncoder().encodeToString(root).equals(v.asText()))throw new IllegalArgumentException();}delegate=new AesGcmCredentialProtector(text(n,"activeKeyId"),roots);
            }finally{Arrays.fill(bytes,(byte)0);}
        }catch(IOException|RuntimeException invalid){throw new IllegalStateException("Credential keyring configuration is invalid");}finally{roots.values().forEach(b->Arrays.fill(b,(byte)0));}
    }
    public boolean available(){return delegate.available();} public String activeKeyId(){return delegate.activeKeyId();}
    public boolean canOpen(String keyId){return delegate.canOpen(keyId);}
    public SourceCredential.Envelope seal(Scope scope,char[] secret){return delegate.seal(scope,secret);} public char[] open(Scope scope,SourceCredential.Envelope envelope){return delegate.open(scope,envelope);}
    public String authenticate(String keyId,List<String> parts,char[] secret){return delegate.authenticate(keyId,parts,secret);}
    @Override public String toString(){return "CredentialVault[redacted]";}
}
