package com.acme.opsweave.platform.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.acme.opsweave.integration.domain.ProblemReadException;
import java.lang.reflect.Method;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

class RegisteredProblemBoundaryTest {
    @Test
    void routeMatchesThePublicSourceIdPlaceholder() throws Exception {
        Method read = RegisteredProblemController.class.getDeclaredMethod(
            "read", UUID.class, int.class, HttpServletRequest.class);
        assertEquals(
            "/api/v2/data-sources/{sourceId}/connection/{revision}/problems",
            read.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("sourceId", read.getParameters()[0].getAnnotation(PathVariable.class).value());
    }

    @Test
    void registeredProblemSourceFailuresUseStableHttpStatuses() {
        var errors = new WorkflowErrors();
        assertEquals(403, errors.problem(new ProblemReadException(ProblemReadException.Code.FORBIDDEN))
            .getStatusCode().value());
        assertEquals(429, errors.problem(new ProblemReadException(ProblemReadException.Code.SOURCE_BUSY))
            .getStatusCode().value());
        assertEquals(503, errors.problem(new ProblemReadException(ProblemReadException.Code.INVALID_SOURCE_RESPONSE))
            .getStatusCode().value());
    }
}
