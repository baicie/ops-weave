package com.acme.opsweave.platform.integration;

import com.acme.opsweave.integration.domain.PipelineException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {HostPipelineController.class, ZabbixHostSyncController.class, PipelineReplayController.class, PipelineDraftController.class, SourceScanRunController.class, SourceConnectionCheckController.class})
public class PipelineErrors {
    @ExceptionHandler(com.acme.opsweave.integration.domain.SourceConnectionCheckException.class)
    public ResponseEntity<?> connectionCheck(com.acme.opsweave.integration.domain.SourceConnectionCheckException failed) {
        int status = switch (failed.code()) {
            case FORBIDDEN -> 403;
            case INVALID_REQUEST -> 400;
            case UNCONFIGURED -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("error", failed.code().name()));
    }
    @ExceptionHandler(com.acme.opsweave.integration.domain.SourceScanRunException.class)
    public ResponseEntity<?> scanRun(com.acme.opsweave.integration.domain.SourceScanRunException failed) {
        int status = switch (failed.code()) {
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case INVALID_REQUEST -> 400;
            case UNCONFIGURED -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("error", failed.code().name()));
    }
    @ExceptionHandler(PipelineException.class)
    public ResponseEntity<?> pipeline(PipelineException failed) {
        int status = switch (failed.code()) {
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case INVALID_REQUEST -> 400;
            case REPLAY_BUSY -> 503;
            default -> 409;
        };
        return ResponseEntity.status(status).body(Map.of("error", failed.code().name()));
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class, java.io.IOException.class})
    public ResponseEntity<?> invalid(Exception failed) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST"));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> unavailable(IllegalStateException failed) {
        return ResponseEntity.status(503).body(Map.of("error", "PIPELINE_STORE_UNAVAILABLE"));
    }
}
