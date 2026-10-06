package com.acme.opsweave.platform.catalog;

import com.acme.opsweave.catalog.domain.CatalogFailure;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = {ModelCatalogController.class,ModelImpactController.class})
public class CatalogErrors {
    @ExceptionHandler(CatalogFailure.class)
    public ResponseEntity<?> failure(CatalogFailure failure) {
        return ResponseEntity.status(switch (failure.code()) { case FORBIDDEN -> 403; case NOT_FOUND -> 404; case UNAVAILABLE -> 503; default -> 409; }).body(Map.of("error", failure.code().name()));
    }
    @ExceptionHandler({IllegalArgumentException.class, java.io.IOException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception ignored) { return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST")); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> unavailable(Exception ignored) { return ResponseEntity.status(503).body(Map.of("error", "CATALOG_UNAVAILABLE")); }
    @ExceptionHandler(com.acme.opsweave.integration.domain.WorkflowFailure.class)
    public ResponseEntity<?> workflow(com.acme.opsweave.integration.domain.WorkflowFailure failure){return ResponseEntity.status(failure.code()==com.acme.opsweave.integration.domain.WorkflowFailure.Code.FORBIDDEN?403:503).body(Map.of("error",failure.code()==com.acme.opsweave.integration.domain.WorkflowFailure.Code.FORBIDDEN?"FORBIDDEN":"REFERENCES_UNAVAILABLE"));}
    @ExceptionHandler(com.acme.opsweave.integration.domain.PipelineException.class)
    public ResponseEntity<?> source(com.acme.opsweave.integration.domain.PipelineException failure){return ResponseEntity.status(failure.code()==com.acme.opsweave.integration.domain.PipelineException.Code.FORBIDDEN?403:503).body(Map.of("error",failure.code()==com.acme.opsweave.integration.domain.PipelineException.Code.FORBIDDEN?"FORBIDDEN":"REFERENCES_UNAVAILABLE"));}
}
