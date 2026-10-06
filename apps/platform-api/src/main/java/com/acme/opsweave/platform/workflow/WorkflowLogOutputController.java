package com.acme.opsweave.platform.workflow;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.application.WorkflowLogOutputService;
import com.acme.opsweave.integration.domain.WorkflowLogOutput;
import com.acme.opsweave.platform.catalog.CatalogJson;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.platform.telemetry.ClickHouseWorkflowLogSink;
import com.acme.opsweave.platform.telemetry.ClickHouseWorkflowLogWindowSink;
import com.acme.opsweave.integration.api.WorkflowLogWindowSink;
import com.acme.opsweave.integration.domain.WorkflowLogWindow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import static com.acme.opsweave.platform.integration.PipelineJson.*;
@RestController
@RequestMapping("/api/v1/integrations/workflows/log-outputs")
public class WorkflowLogOutputController{
 private final PrincipalContext principals;private final WorkflowController workflows;private final WorkflowLogOutputService service;private final boolean configured;private final WorkflowLogWindowSink windows;private final WorkflowLogWindowSink replays;
 public WorkflowLogOutputController(PrincipalContext principals,WorkflowController workflows,InventoryWiring wiring,WorkflowMetricOutputController metrics,@Value("${opsweave.logs.url:}")String url,@Value("${opsweave.logs.username:}")String user,@Value("${opsweave.logs.password:}")String password){
  this.principals=principals;this.workflows=workflows;configured="postgres".equals(wiring.label())&&!url.isBlank()&&!user.isBlank()&&!password.isBlank();
  var sink=configured?new ClickHouseWorkflowLogSink(URI.create(url),user,password):new WorkflowLogOutputService.Sink(){public boolean ready(){return false;}public void write(WorkflowLogOutput.Batch batch){throw new WorkflowLogOutputService.OutputFailure(false);}public List<WorkflowLogOutput.Record> read(WorkflowLogOutput.Scope scope){throw new WorkflowLogOutputService.OutputFailure(false);}};
  service=new WorkflowLogOutputService(wiring.workflows(),workflows.service(),sink,Clock.systemUTC(),metrics.budget());
  windows=configured?new ClickHouseWorkflowLogWindowSink(URI.create(url),user,password):new WorkflowLogWindowSink(){public boolean ready(){return false;}public void write(WorkflowLogWindow.Batch batch){throw new WorkflowLogOutputService.OutputFailure(false);}public List<WorkflowLogWindow.Record> read(WorkflowLogOutput.Scope scope){throw new WorkflowLogOutputService.OutputFailure(false);}};
  replays=configured?ClickHouseWorkflowLogWindowSink.replay(URI.create(url),user,password):windows;
 }
 public boolean configured(){return configured;}
 public WorkflowLogWindowSink windowSink(){return windows;}
 public WorkflowLogWindowSink replaySink(){return replays;}
 private Principal principal(HttpServletRequest request){if(request.getQueryString()!=null)throw new IllegalArgumentException();var p=principals.requirePrincipal();workflows.service().authorize(p);return p;}
 @GetMapping("/workflows/{id}/capability")public Object capability(@PathVariable String id,HttpServletRequest request){var p=principal(request);com.acme.opsweave.integration.domain.WorkflowDefinition.ref(id,1);return Map.of("schemaVersion","2.0","mode","MANUAL_LOG_BATCH","available",configured&&service.available(),"maxRecords",5,"readAllowed",service.permitted(p,id,Permission.LOG_READ),"writeAllowed",service.permitted(p,id,Permission.LOG_WRITE));}
 @GetMapping("/workflows/{id}/{revision}/capability")public Object sourceCapability(@PathVariable String id,@PathVariable int revision,HttpServletRequest request){var p=principal(request);var entry=workflows.service().version(p,id,revision);if(!entry.definition().source().kind().equals("ZABBIX_LOG")||!entry.definition().target().kind().equals("LOG"))throw new IllegalArgumentException();return Map.of("schemaVersion","2.0","mode","SOURCE_LOG_SAMPLE","available",configured&&service.available(),"maxRecords",5,"readAllowed",service.permitted(p,id,Permission.LOG_READ),"writeAllowed",service.permitted(p,id,Permission.LOG_WRITE));}
 @PostMapping(value="/source",consumes="application/json")public Object sourceWrite(HttpServletRequest request)throws java.io.IOException{var p=principal(request);var node=CatalogJson.read(request);fields(node,Set.of("requestId","id","revision","digest"));return service.write(p,new WorkflowLogOutput.SourceCommand(SourceConnectionJson.uuid(node,"requestId"),text(node,"id"),integer(node,"revision",null),text(node,"digest")));}
 @PostMapping(consumes="application/json")public Object write(HttpServletRequest request)throws java.io.IOException{var p=principal(request);var node=CatalogJson.read(request);fields(node,Set.of("requestId","id","revision","digest","previewId","samples"));return service.write(p,new WorkflowLogOutput.Command(UUID.fromString(text(node,"requestId")),text(node,"id"),integer(node,"revision",null),text(node,"digest"),UUID.fromString(text(node,"previewId")),WorkflowJson.samples(node.get("samples"))));}
 @GetMapping("/commands/{id}")public Object receipt(@PathVariable UUID id,HttpServletRequest request){return service.read(principal(request),id);}
 @GetMapping("/workflows/{id}/receipts")public Object records(@PathVariable String id,HttpServletRequest request){return service.records(principal(request),id);}
 @GetMapping("/commands/{id}/verification")public Object verify(@PathVariable UUID id,HttpServletRequest request){return service.confirm(principal(request),id);}
 @GetMapping("/commands/{id}/records")public Object data(@PathVariable UUID id,HttpServletRequest request){return service.data(principal(request),id);}
}
