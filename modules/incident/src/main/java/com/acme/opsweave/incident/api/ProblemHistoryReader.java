package com.acme.opsweave.incident.api;

import com.acme.opsweave.alerting.domain.ProblemObservation;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.*;

public interface ProblemHistoryReader {
    /** Recheck current Incident scope/version; current occurrence ownership and historical entity scope precede limit + 1. */
    List<ProblemObservation> observations(TenantId tenant, UUID incidentId, IncidentVisibility visibility, ProblemHistoryQuery query);
}
