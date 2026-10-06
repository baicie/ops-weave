package com.acme.opsweave.platform.telemetry;

import com.acme.opsweave.telemetry.domain.MetricMappingFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(assignableTypes=MetricMappingController.class)
public final class MetricMappingErrors {
    @ExceptionHandler(MetricMappingFailure.class) public ResponseEntity<?> failure(MetricMappingFailure e) {
        return ResponseEntity.status(switch(e.code()){case FORBIDDEN->403;case NOT_FOUND->404;case UNAVAILABLE->503;default->409;}).body(Map.of("error",e.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class,java.io.IOException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) public ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error","INVALID_REQUEST"));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> unavailable(Exception e) {
        return ResponseEntity.status(503).body(Map.of("error","METRIC_MAPPING_UNAVAILABLE"));
    }
}
