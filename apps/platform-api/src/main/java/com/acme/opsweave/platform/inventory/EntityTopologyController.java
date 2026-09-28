package com.acme.opsweave.platform.inventory;
import com.acme.opsweave.identity.api.*;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.inventory.application.ReadEntityTopology;
import com.acme.opsweave.inventory.domain.EntityTopology;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.EntityId;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/entities/{entityId}/topology")
public final class EntityTopologyController {
    private final PrincipalContext principals;private final ReadEntityTopology read;
    public EntityTopologyController(PrincipalContext principals,AuthorizationService auth,InventoryWiring wiring) { this.principals=principals;this.read=new ReadEntityTopology(auth,wiring.topology(),Clock.systemUTC()); }
    @GetMapping public ResponseEntity<?> get(@PathVariable String entityId,HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty() || !entityId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid topology request");
        var p=principals.requirePrincipal();if(!p.has(Permission.ENTITY_READ))return ResponseEntity.status(403).body(Map.of("error","forbidden"));
        var view=read.read(p,EntityId.parse(entityId));if(view.isEmpty())return ResponseEntity.status(404).body(Map.of("error","not_found"));
        var t=view.orElseThrow();var body=new LinkedHashMap<String,Object>();body.put("schemaVersion","1.0");body.put("tenantId",t.tenant().value());body.put("centerId",t.center().value().toString());body.put("asOf",t.asOf().toString());body.put("coverage","stored-current-one-hop");body.put("limit",EntityTopology.LIMIT);body.put("truncated",t.truncated());
        body.put("nodes",t.nodes().stream().map(n->Map.of("id",n.id().value().toString(),"name",n.name(),"type",n.type(),"lifecycle",n.lifecycle(),"dataMode",n.dataMode())).toList());
        body.put("edges",t.edges().stream().map(e->{var m=new LinkedHashMap<String,Object>();m.put("id",e.id().toString());m.put("from",e.from().value().toString());m.put("to",e.to().value().toString());m.put("type",e.type());m.put("validFrom",e.validFrom().toString());m.put("validTo",e.validTo()==null?null:e.validTo().toString());m.put("dataMode",e.dataMode());return m;}).toList());return ResponseEntity.ok(body);
    }
}
