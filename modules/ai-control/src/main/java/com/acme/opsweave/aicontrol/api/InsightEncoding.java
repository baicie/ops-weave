package com.acme.opsweave.aicontrol.api;
import com.acme.opsweave.aicontrol.domain.*;
public interface InsightEncoding {
    String digest(InsightSubmission submission);
    int bytes(AiInsight result);
}
