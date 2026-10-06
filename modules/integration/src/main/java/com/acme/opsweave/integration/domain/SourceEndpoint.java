package com.acme.opsweave.integration.domain;

import java.util.*;

/** Public, non-secret snapshot of a deployment-registered connector destination. */
public record SourceEndpoint(String id,String name,String connectorKind,String address,String digest) {
    public SourceEndpoint {
        if(id==null||!id.matches("[a-z][a-z0-9_-]{0,63}")||!"ZABBIX_HOST".equals(connectorKind)
            ||address==null||address.length()>1024||!address.matches("https?://(?:[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+|\\[[0-9A-Fa-f:]+\\])(?::[1-9][0-9]{0,4})?/api_jsonrpc\\.php"))throw new IllegalArgumentException("Invalid source endpoint");
        SourceCredential.checkName(name);WorkflowDefinition.checkDigest(digest);
        if(!digest.equals(fingerprint(id,connectorKind,address)))throw new IllegalArgumentException("Invalid source endpoint digest");
    }
    public static SourceEndpoint registered(String id,String name,String address) {return new SourceEndpoint(id,name,"ZABBIX_HOST",address,fingerprint(id,"ZABBIX_HOST",address));}
    public static String fingerprint(String id,String kind,String address) {return WorkflowDefinition.hash(List.of("source-endpoint-v2",id,kind,address));}
    public Pin pin(){return new Pin(id,digest);}
    public record Pin(String id,String digest) {
        public Pin {if(id==null||!id.matches("[a-z][a-z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid source endpoint pin");WorkflowDefinition.checkDigest(digest);}
    }
}
