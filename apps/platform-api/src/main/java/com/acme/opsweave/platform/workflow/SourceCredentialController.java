package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.integration.application.SourceCredentialService;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v2/data-sources/credentials")
public final class SourceCredentialController {
    private final PrincipalContext principals;private final InventoryWiring wiring;private final SourceCredentialService service;private final com.acme.opsweave.platform.OpsweaveProperties properties;
    public SourceCredentialController(PrincipalContext principals,InventoryWiring wiring,SourceCredentialVault vault,com.acme.opsweave.platform.OpsweaveProperties properties){this.principals=principals;this.wiring=wiring;this.properties=properties;service=new SourceCredentialService(wiring.workflows(),vault,Clock.systemUTC());}
    private boolean secretTransport(HttpServletRequest r){return secretTransport(r,properties);}
    static boolean secretTransport(HttpServletRequest r,com.acme.opsweave.platform.OpsweaveProperties properties){var auth=properties.auth();return r.isSecure()||auth!=null&&"dev".equals(auth.mode())&&auth.bindLoopbackOnly()&&loopback(r.getRemoteAddr())&&loopback(r.getLocalAddr());}
    private static boolean loopback(String value){return Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(value);}
    private void query(HttpServletRequest r){if(!r.getParameterMap().isEmpty()||!r.getRequestURI().matches("/api/v2/data-sources/credentials(?:/[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}(?:/versions(?:/[1-9][0-9]?|/100)/revoke|/versions|/commands/[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12})?)?"))throw new IllegalArgumentException();}
    private static UUID key(tools.jackson.databind.JsonNode n){String value=text(n,"requestId");var id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}
    private Object receipt(com.acme.opsweave.integration.domain.SourceCredential.Receipt r){return Map.of("schemaVersion","2.0","storage",wiring.label(),"receipt",SourceCredentialJson.publicReceipt(r));}
    @GetMapping public Object list(HttpServletRequest r){query(r);var page=service.list(principals.requirePrincipal());return Map.of("schemaVersion","2.0","storage",wiring.label(),"items",page.items().stream().map(SourceCredentialJson::wire).toList(),"truncated",page.truncated(),"vaultAvailable",page.vaultAvailable(),"canConfigure",page.canConfigure(),"secretWriteAvailable",page.vaultAvailable()&&secretTransport(r));}
    @GetMapping("/{id}")public Object read(@PathVariable UUID id,HttpServletRequest r){query(r);return Map.of("schemaVersion","2.0","storage",wiring.label(),"credential",SourceCredentialJson.wire(service.read(principals.requirePrincipal(),id)));}
    @GetMapping("/{id}/versions")public Object versions(@PathVariable UUID id,HttpServletRequest r){query(r);return Map.of("schemaVersion","2.0","storage",wiring.label(),"credentialId",id,"items",service.versions(principals.requirePrincipal(),id));}
    @GetMapping("/{id}/commands/{requestId}")public Object original(@PathVariable UUID id,@PathVariable UUID requestId,HttpServletRequest r){query(r);return receipt(service.receipt(principals.requirePrincipal(),id,requestId));}
    @PostMapping(consumes="application/json")public Object create(HttpServletRequest r)throws java.io.IOException{return write(null,r);}
    @PatchMapping(value="/{id}",consumes="application/json")public Object edit(@PathVariable UUID id,HttpServletRequest r)throws java.io.IOException{return write(id,r);}
    private Object write(UUID id,HttpServletRequest r)throws java.io.IOException {
        query(r);var p=principals.requirePrincipal();var n=CatalogJson.read(r);boolean create=id==null;fields(n,create?Set.of("requestId","name","secret"):Set.of("requestId","expectedEditVersion","name","state","secret"));var request=key(n);if(create)id=request;
        var raw=n.get("secret");if(raw==null||!raw.isNull()&&!raw.isString())throw new IllegalArgumentException();char[] secret=raw.isNull()?null:raw.asText().toCharArray();
        try{if(secret!=null&&!secretTransport(r))throw new com.acme.opsweave.integration.domain.SourceCredentialFailure(com.acme.opsweave.integration.domain.SourceCredentialFailure.Code.UNAVAILABLE);try(var command=new SourceCredentialService.Write(request,create?0:integer(n,"expectedEditVersion",null),text(n,"name"),create?"ACTIVE":text(n,"state"),secret)){return receipt(service.write(p,id,command,create));}}finally{if(secret!=null)Arrays.fill(secret,'\0');}
    }
    @PostMapping(value="/{id}/versions/{revision}/revoke",consumes="application/json")public Object revoke(@PathVariable UUID id,@PathVariable int revision,HttpServletRequest r)throws java.io.IOException {query(r);var p=principals.requirePrincipal();var n=CatalogJson.read(r);fields(n,Set.of("requestId","expectedEditVersion"));return receipt(service.revoke(p,id,key(n),integer(n,"expectedEditVersion",null),revision));}
}
