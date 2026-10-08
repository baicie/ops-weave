package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.application.ModelSpendService;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.platform.incident.IncidentController;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/model-calls")
public final class ModelSpendController {
    private final ModelSpendService service;private final PrincipalContext principals;private final RuntimeOrigin origin;private final String storage;private final ToolExecutor executor;
    public ModelSpendController(ModelSpendService service,PrincipalContext principals,RuntimeOrigin origin,InventoryWiring wiring,ToolExecutor executor){this.service=service;this.principals=principals;this.origin=origin;storage=wiring.label();this.executor=executor;}
    private Object response(com.acme.opsweave.aicontrol.domain.ModelSpend.Call call){return Map.of("storage",storage,"record",ModelSpendJson.wire(call,Instant.now()));}
    @PostMapping(value="/reservations",consumes="application/json") public Object reserve(HttpServletRequest request) throws IOException {
        origin.require(request);if(!request.getParameterMap().isEmpty())throw new IllegalArgumentException();var n=ModelSpendJson.read(request);
        ModelSpendJson.exact(n,"runId","sessionId","inputDigest","inputBytes");
        var p=principals.requirePrincipal();var run=ModelSpendJson.uuid(n,"runId");var session=ModelSpendJson.uuid(n,"sessionId");var digest=ModelSpendJson.text(n,"inputDigest");int bytes=ModelSpendJson.integer(n,"inputBytes");
        return executor.run(()->response(service.reserve(p,run,session,digest,bytes)));
    }
    @PostMapping(value="/reports",consumes="application/json") public Object report(HttpServletRequest request) throws IOException {
        origin.require(request);if(!request.getParameterMap().isEmpty())throw new IllegalArgumentException();var n=ModelSpendJson.read(request);
        ModelSpendJson.exact(n,"runId","sessionId","usage");var p=principals.requirePrincipal();var run=ModelSpendJson.uuid(n,"runId");var session=ModelSpendJson.uuid(n,"sessionId");var usage=ModelSpendJson.usage(n.get("usage"));return executor.run(()->response(service.report(p,run,session,usage)));
    }
    @GetMapping("/{runId}") public Object get(@PathVariable String runId,HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw new IllegalArgumentException();var p=principals.requirePrincipal();var id=IncidentController.uuid(runId);return executor.run(()->response(service.get(p,id)));}
    @GetMapping("/metrics") public Object metrics(HttpServletRequest request) {
        var allowed=Set.of("from","to","model","limit");
        if(request.getParameterMap().entrySet().stream().anyMatch(entry -> entry.getValue().length!=1 || !allowed.contains(entry.getKey()))) throw new IllegalArgumentException();
        var from=Instant.parse(required(request,"from")); var to=Instant.parse(required(request,"to"));
        var model=request.getParameter("model"); if(model!=null && model.isEmpty()) throw new IllegalArgumentException();
        var rawLimit=request.getParameter("limit"); var limit=rawLimit==null?100:Integer.parseInt(rawLimit);
        var query=new com.acme.opsweave.aicontrol.domain.ModelSpend.MetricsQuery(from,to,model,limit); var p=principals.requirePrincipal();
        return executor.run(()->{
            var result=new LinkedHashMap<String,Object>(); result.put("schemaVersion","1.0"); result.put("storage",storage);
            result.put("from",from.toString()); result.put("to",to.toString()); result.put("model",model);
            result.put("items",service.metrics(p,query).stream().map(ModelSpendJson::metric).toList()); return result;
        });
    }
    private static String required(HttpServletRequest request,String name) { var value=request.getParameter(name); if(value==null || value.isEmpty()) throw new IllegalArgumentException(); return value; }
}
