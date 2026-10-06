package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.application.WorkflowMetricOutputService;
import com.acme.opsweave.integration.domain.WorkflowMetricOutput;
import com.acme.opsweave.platform.MetricsQueryProperties;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.platform.telemetry.VictoriaWorkflowMetricSink;
import com.acme.opsweave.telemetry.domain.MetricWriteBatch;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;

@RestController
@RequestMapping("/api/v1/integrations/workflows/metric-outputs")
public class WorkflowMetricOutputController {
    private final PrincipalContext principals;private final WorkflowController workflows;private final WorkflowMetricOutputService service;private final boolean available;private final WorkflowMetricOutputService.Sink sink;private final WorkflowMetricOutputService.Sink streamSink;private final java.util.concurrent.Semaphore budget=new java.util.concurrent.Semaphore(2);
    public WorkflowMetricOutputController(PrincipalContext principals,WorkflowController workflows,InventoryWiring wiring,MetricsQueryProperties properties) {
        this.principals=principals;this.workflows=workflows;
        available="postgres".equals(wiring.label())&&properties.victoriaUrl()!=null&&!properties.victoriaUrl().isBlank();
        sink=available?new VictoriaWorkflowMetricSink(URI.create(properties.victoriaUrl())):new WorkflowMetricOutputService.Sink(){public void write(MetricWriteBatch batch){throw new WorkflowMetricOutputService.OutputFailure(false);}public List<MetricWriteBatch.Sample> read(Map<String,String> labels,List<Long> timestamps){throw new WorkflowMetricOutputService.OutputFailure(false);}};
        streamSink=available?new VictoriaWorkflowMetricSink(URI.create(properties.victoriaUrl()),java.time.Duration.ofSeconds(5)):sink;
        service=new WorkflowMetricOutputService(wiring.workflows(),workflows.service(),sink,Clock.systemUTC(),budget);
    }
    public boolean available(){return available;}
    public WorkflowMetricOutputService.Sink streamSink(){return streamSink;}
    public java.util.concurrent.Semaphore budget(){return budget;}
    private Principal principal(HttpServletRequest request){if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principals.requirePrincipal();workflows.service().authorize(p);return p;}
    @GetMapping public Object capability(HttpServletRequest request){principal(request);return Map.of("schemaVersion","2.0","mode","FIXED_METRIC_SAMPLE","available",available,"maxPoints",5);}
    @PostMapping(consumes="application/json")public Object write(HttpServletRequest request)throws IOException {
        var p=principal(request);if(!available)throw new com.acme.opsweave.integration.domain.WorkflowFailure(com.acme.opsweave.integration.domain.WorkflowFailure.Code.SOURCE_UNAVAILABLE);
        var node=CatalogJson.read(request);fields(node,Set.of("requestId","id","revision","digest","previewId","samples"));
        return service.write(p,new WorkflowMetricOutput.Command(UUID.fromString(text(node,"requestId")),text(node,"id"),integer(node,"revision",null),text(node,"digest"),UUID.fromString(text(node,"previewId")),WorkflowJson.samples(node.get("samples"))));
    }
    @GetMapping("/commands/{id}")public Object receipt(@PathVariable UUID id,HttpServletRequest request){return service.read(principal(request),id);}
    @GetMapping("/workflows/{id}/receipts")public Object records(@PathVariable String id,HttpServletRequest request){return service.records(principal(request),id);}
    @GetMapping("/commands/{id}/verification")public Object verify(@PathVariable UUID id,HttpServletRequest request){return service.confirm(principal(request),id);}
    @GetMapping("/commands/{id}/points")public Object data(@PathVariable UUID id,HttpServletRequest request){return service.data(principal(request),id);}
}
