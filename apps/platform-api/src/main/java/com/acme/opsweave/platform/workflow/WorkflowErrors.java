package com.acme.opsweave.platform.workflow;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.catalog.domain.CatalogFailure;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes={WorkflowController.class,SourceSetupController.class,WorkflowRuntimeController.class,SourceInstanceController.class,SourceInspectionController.class,SourceCredentialController.class,SourceEndpointController.class,SourceConnectionController.class,RegisteredItemSyncController.class,RegisteredItemScanRunController.class,RegisteredMetricHistoryController.class,WorkflowMetricSourceController.class,WorkflowMetricOutputController.class,WorkflowLogOutputController.class,WorkflowLogStreamController.class,WorkflowQualityController.class,WorkflowQualityAlertsController.class,WorkflowHistoryController.class,WorkflowMetricReplayController.class,WorkflowLogReplayController.class,WorkflowRecoveryController.class,WorkflowSampleRecoveryController.class,WorkflowMetricStreamController.class,WorkflowHostScheduleController.class})
public class WorkflowErrors {
 @ExceptionHandler(SourceCredentialFailure.class)public ResponseEntity<?> credential(SourceCredentialFailure e){return ResponseEntity.status(switch(e.code()){case FORBIDDEN->403;case NOT_FOUND->404;case UNAVAILABLE->503;default->409;}).body(Map.of("error","CREDENTIAL_"+e.code().name()));}
 @ExceptionHandler(SourceScanRunException.class)public ResponseEntity<?> scanRun(SourceScanRunException e){return ResponseEntity.status(switch(e.code()){case FORBIDDEN->403;case NOT_FOUND->404;case INVALID_REQUEST->400;case UNCONFIGURED->503;}).body(Map.of("error",e.code().name()));}
 @ExceptionHandler(WorkflowFailure.class)public ResponseEntity<?> failure(WorkflowFailure e){return ResponseEntity.status(switch(e.code()){case FORBIDDEN->403;case NOT_FOUND->404;case BUSY->429;case SOURCE_UNAVAILABLE->503;case INVALID_SAMPLE->400;default->409;}).body(Map.of("error",e.code().name()));}
 @ExceptionHandler(PipelineException.class)public ResponseEntity<?> source(PipelineException e){return ResponseEntity.status(e.code()==PipelineException.Code.FORBIDDEN?403:e.code()==PipelineException.Code.NOT_FOUND?404:503).body(Map.of("error",e.code().name()));}
 @ExceptionHandler(CatalogFailure.class)public ResponseEntity<?> model(CatalogFailure e){return ResponseEntity.status(e.code()==CatalogFailure.Code.FORBIDDEN?403:e.code()==CatalogFailure.Code.NOT_FOUND?404:409).body(Map.of("error",e.code().name()));}
 @ExceptionHandler({IllegalArgumentException.class,java.io.IOException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})public ResponseEntity<?> invalid(Exception ignored){return ResponseEntity.badRequest().body(Map.of("error","INVALID_REQUEST"));}
 @ExceptionHandler(IllegalStateException.class)public ResponseEntity<?> unavailable(Exception ignored){return ResponseEntity.status(503).body(Map.of("error","WORKFLOW_UNAVAILABLE"));}
}
