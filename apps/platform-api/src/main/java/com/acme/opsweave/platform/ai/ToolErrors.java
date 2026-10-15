package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.ToolFailure;
import com.acme.opsweave.incident.domain.IncidentFailure;
import java.time.DateTimeException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = {ToolController.class, InsightController.class, DiagnosisController.class, ModelSpendController.class, AiRetentionController.class, AiRunController.class, SkillController.class})
public final class ToolErrors {
    /** Errors are written as JSON whatever the client asked for (an SSE reader still gets JSON errors). */
    private static ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("error", code));
    }

    @ExceptionHandler(ToolFailure.class)
    ResponseEntity<Map<String, String>> tool(ToolFailure error) {
        int status = switch (error.code()) {
            case INVALID_REQUEST -> 400; case FORBIDDEN -> 403; case NOT_FOUND -> 404; case INPUT_CHANGED -> 409;
            case EXPIRED -> 410; case BUDGET_EXHAUSTED, BUSY -> 429; case DEADLINE -> 504; case READ_LIMIT, UNAVAILABLE -> 503;
        }; return error(status, error.code().name());
    }

    /** Durable run registry conflicts: a reused request id or an illegal state transition. */
    @ExceptionHandler(com.acme.opsweave.aicontrol.domain.AiRun.Conflict.class)
    ResponseEntity<Map<String, String>> run(com.acme.opsweave.aicontrol.domain.AiRun.Conflict failed) {
        return error(409, failed.code());
    }
    /** Registry commands preserve their closed conflict code without disclosing submitted content. */
    @ExceptionHandler(com.acme.opsweave.aicontrol.domain.SkillRelease.Conflict.class)
    ResponseEntity<Map<String, String>> skill(com.acme.opsweave.aicontrol.domain.SkillRelease.Conflict failed) {
        return error(409, failed.code());
    }
    @ExceptionHandler(IncidentFailure.class)
    ResponseEntity<Map<String, String>> incident(IncidentFailure error) {
        return tool(new ToolFailure(switch (error.code()) { case FORBIDDEN -> ToolFailure.Code.FORBIDDEN; case NOT_FOUND -> ToolFailure.Code.NOT_FOUND; default -> ToolFailure.Code.UNAVAILABLE; }));
    }
    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class})
    ResponseEntity<Map<String, String>> invalid() { return error(400, "INVALID_REQUEST"); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> unavailable() { return error(503, "UNAVAILABLE"); }
}
