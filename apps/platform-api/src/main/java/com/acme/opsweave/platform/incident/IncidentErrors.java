package com.acme.opsweave.platform.incident;

import com.acme.opsweave.incident.domain.IncidentFailure;
import com.acme.opsweave.platform.integration.ZabbixProblemController;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {IncidentController.class, ZabbixProblemController.class, ProblemHistoryController.class})
public class IncidentErrors {
    @ExceptionHandler(IncidentFailure.class)
    ResponseEntity<?> failure(IncidentFailure error) {
        int status = switch (error.code()) {
            case FORBIDDEN -> 403; case NOT_FOUND -> 404; case INVALID_REQUEST -> 400;
            case CONFLICT, INVALID_TRANSITION, ACTIVE_PROBLEMS -> 409; case READ_LIMIT, UNAVAILABLE -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("error", error.code().name()));
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST")); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("error", "UNAVAILABLE")); }
}
