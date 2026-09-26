package com.acme.opsweave.integration.api;

import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.integration.domain.ProblemReadWindow;
import java.util.List;

public interface ZabbixProblemPort {
    Page read(Connector.SourceContext source, ProblemReadWindow window);
    record Page(List<ExternalProblem> items, String nextAfterEventId) {
        public Page { items = List.copyOf(items); }
    }
}
