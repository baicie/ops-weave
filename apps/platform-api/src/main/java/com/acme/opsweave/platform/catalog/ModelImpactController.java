package com.acme.opsweave.platform.catalog;
import com.acme.opsweave.catalog.application.*;
import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.platform.workflow.WorkflowController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.Set;
import java.io.IOException;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v1/catalog")
public final class ModelImpactController {
    private final PrincipalContext principals;private final ModelCatalogService catalog;private final ModelReferenceService references;
    public ModelImpactController(PrincipalContext principals,InventoryWiring wiring,WorkflowController workflows){this.principals=principals;var builtins=new BuiltinCatalog().definitions();catalog=new ModelCatalogService(wiring.modelCatalog(),builtins,Clock.systemUTC());references=new ModelReferenceService(wiring.modelCatalog(),builtins,workflows.modelReferences(),Clock.systemUTC());}
    @GetMapping("/versions/{id}/{revision}/references")public Object references(@PathVariable String id,@PathVariable int revision,HttpServletRequest request){if(request.getQueryString()!=null)throw new IllegalArgumentException();return java.util.Map.of("schemaVersion","1.0","report",references.references(principals.requirePrincipal(),new ModelDefinition.Ref(id,revision)));}
    @PostMapping(value="/revisions/review",consumes="application/json")public Object review(HttpServletRequest request)throws IOException{if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principals.requirePrincipal();catalog.authorize(p,false);var n=CatalogJson.read(request);fields(n,Set.of("ref","expectedEditVersion","digest"));if(n.size()!=3)throw new IllegalArgumentException();return java.util.Map.of("schemaVersion","1.0","review",catalog.review(p,CatalogJson.ref(n.get("ref")),integer(n,"expectedEditVersion",null),text(n,"digest")));}
}
