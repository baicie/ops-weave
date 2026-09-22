package com.acme.opsweave.ingestion.history;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.ingestion.IngestionApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

@SpringBootTest(classes = IngestionApplication.class, properties = "opsweave.history.enabled=false")
class HistoryWorkerClosedTest {
    @Autowired ApplicationContext context;
    @Test void defaultModeDoesNotCreateSourceSinkCheckpointOrScheduler() {
        assertEquals(0, context.getBeansOfType(HistoryPoller.class).size());
        assertEquals(0, context.getBeansOfType(javax.sql.DataSource.class).size());
        assertEquals(0, context.getBeansOfType(com.acme.opsweave.integration.application.IngestMetricHistoryUseCase.class).size());
    }
}
