package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.incident.domain.IncidentFailure;
import java.time.DateTimeException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = {ToolController.class, InsightController.class, DiagnosisController.class, ModelSpendController.class, AiRetentionController.class})
public final class ToolErrors {
    @ExceptionHandler(ToolFailure.class)
    ResponseEntity<?> tool(ToolFailure error) {
        int status = switch (error.code()) {
            case INVALID_REQUEST -> 400; case FORBIDDEN -> 403; case NOT_FOUND -> 404; case INPUT_CHANGED -> 409;
            case EXPIRED -> 410; case BUDGET_EXHAUSTED, BUSY -> 429; case DEADLINE -> 504; case READ_LIMIT, UNAVAILABLE -> 503;
        }; return ResponseEntity.status(status).body(Map.of("error", error.code().name()));
    }
    @ExceptionHandler(IncidentFailure.class)
    ResponseEntity<?> incident(IncidentFailure error) {
        return tool(new ToolFailure(switch (error.code()) { case FORBIDDEN -> ToolFailure.Code.FORBIDDEN; case NOT_FOUND -> ToolFailure.Code.NOT_FOUND; default -> ToolFailure.Code.UNAVAILABLE; }));
    }
    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class})
    ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST")); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> unavailable() { return ResponseEntity.status(503).body(Map.of("error", "UNAVAILABLE")); }
}
