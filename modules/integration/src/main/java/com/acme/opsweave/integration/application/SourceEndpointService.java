package com.acme.opsweave.integration.application;

import com.acme.opsweave.integration.api.SourceEndpointCatalog;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.identity.domain.*;
import java.util.*;

/** Scope-filtered metadata reads and exact address pins; no HTTP or arbitrary connector dispatch. */
public final class SourceEndpointService {
    private final SourceEndpointCatalog catalog;
    public SourceEndpointService(SourceEndpointCatalog catalog){this.catalog=Objects.requireNonNull(catalog);}
    private boolean allowed(Principal p,String id,Permission permission){return new Authorizer().decide(p,new ResourceRef(p.tenantId(),"source-endpoint",id),permission).allowed();}
    public List<SourceEndpoint> list(Principal p){
        if(!p.has(Permission.SOURCE_SYNC)&&!p.has(Permission.SOURCE_CONFIGURE))throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        var rows=catalog.list(p.tenantId());if(rows.size()>32||rows.stream().map(SourceEndpoint::id).distinct().count()!=rows.size())throw new IllegalStateException("Source endpoint catalog unavailable");
        return rows.stream().filter(e->allowed(p,e.id(),Permission.SOURCE_SYNC)||allowed(p,e.id(),Permission.SOURCE_CONFIGURE)).sorted(Comparator.comparing(SourceEndpoint::id)).toList();
    }
    public SourceEndpoint read(Principal p,String id){
        new SourceEndpoint.Pin(id,"sha256:"+"0".repeat(64));
        if(!allowed(p,id,Permission.SOURCE_SYNC)&&!allowed(p,id,Permission.SOURCE_CONFIGURE))throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        var row=catalog.find(p.tenantId(),id).orElseThrow(()->new WorkflowFailure(WorkflowFailure.Code.NOT_FOUND));
        if(!row.id().equals(id))throw new IllegalStateException("Source endpoint catalog unavailable");return row;
    }
    public SourceEndpoint requireReadPin(Principal p,SourceEndpoint.Pin pin){
        if(!allowed(p,pin.id(),Permission.SOURCE_SYNC))throw new WorkflowFailure(WorkflowFailure.Code.FORBIDDEN);
        var endpoint=read(p,pin.id());if(!endpoint.digest().equals(pin.digest()))throw new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);return endpoint;
    }
}
