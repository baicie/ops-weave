package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public final class SourceConnectionWiring {
    private final SourceConnectionService service;private final SourceSetupService.Connections connections;
    public SourceConnectionWiring(InventoryWiring wiring,RegisteredSourceEndpoints endpoints,SourceCredentialVault vault,OpsweaveProperties properties){
        service=new SourceConnectionService(wiring.workflows(),new SourceEndpointService(endpoints),new SourceCredentialService(wiring.workflows(),vault,Clock.systemUTC()),Clock.systemUTC());var legacy=new ManagedSourceConnections(properties);
        connections=new SourceSetupService.Connections(){
            public SourceSetupService.Connection resolve(Principal p,WorkflowDefinition.Source source){return legacy.resolve(p,source);}
            public SourceSetupService.Connection resolve(Principal p,SourceInstance instance,WorkflowStore.Session session){var fixed=service.resolve(p,instance,session);return fixed==null?legacy.resolve(p,instance.source()):fixed;}
        };
    }
    public SourceConnectionService service(){return service;}public SourceSetupService.Connections connections(){return connections;}
}
