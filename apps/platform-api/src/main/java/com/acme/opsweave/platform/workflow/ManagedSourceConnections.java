package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.SourceSetupService;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import java.net.URI;
import java.util.*;

/** Only deployment-registered profiles are selectable. Request bodies never supply endpoints or secret references. */
public final class ManagedSourceConnections implements SourceSetupService.Connections {
    private final OpsweaveProperties properties;
    public ManagedSourceConnections(OpsweaveProperties properties){this.properties=properties;}
    public Map<String,Object> profile(Principal p,WorkflowDefinition.Source source) {
        if(new Authorizer().decide(p,ResourceRef.source(p.tenantId(),source.instanceId()),Permission.SOURCE_SYNC).denied())throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        if(source.kind().equals("MANUAL_SAMPLE")) {
            var value=new LinkedHashMap<String,Object>();value.put("instanceId","manual");value.put("digest",WorkflowDefinition.hash(List.of("manual-samples-v1")));value.put("dataMode","MANUAL_SAMPLE");value.put("endpoint",null);value.put("credentialRef",null);return value;
        }
        var z=properties.zabbix();
        if(z==null || !source.instanceId().equals(z.sourceInstanceId()) || !Set.of("jsonrpc","fixture").contains(Objects.toString(z.mode(),"")))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        String endpoint=null,reference=null;
        if(z.mode().equals("jsonrpc")){
            try{var u=URI.create(z.url());if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||z.url().length()>1024)throw new IllegalArgumentException();endpoint=u.toString();}catch(RuntimeException invalid){throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);}
            if(z.secretRef()==null||!z.secretRef().matches("env:[A-Z][A-Z0-9_]{0,127}"))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);
            reference=z.secretRef();
        }
        String digest=WorkflowDefinition.hash(List.of("managed-zabbix-config-v1",z.sourceInstanceId(),z.mode(),Objects.toString(endpoint,""),Objects.toString(reference,"")));
        var value=new LinkedHashMap<String,Object>();value.put("instanceId",z.sourceInstanceId());value.put("digest",digest);value.put("dataMode",z.mode().equals("fixture")?"fixture":"zabbix-jsonrpc");value.put("endpoint",endpoint);value.put("credentialRef",reference);return value;
    }
    public SourceSetupService.Connection resolve(Principal p,WorkflowDefinition.Source source){var profile=profile(p,source);return new SourceSetupService.Connection((String)profile.get("digest"),(String)profile.get("dataMode"));}
}
