package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** An immutable decision about one uncertain sample; the original output proof stays unchanged. */
public final class WorkflowSampleRecovery {
    private WorkflowSampleRecovery() {}
    public enum Kind { METRIC_SAMPLE, LOG_SAMPLE }
    public record Command(UUID requestId,String id,int revision,String digest,Kind kind,UUID batchId,
                          String batchDigest,Instant expectedUpdatedAt,boolean acknowledgeUncertainOutput) {
        public Command {
            Objects.requireNonNull(requestId);Objects.requireNonNull(kind);Objects.requireNonNull(batchId);Objects.requireNonNull(expectedUpdatedAt);
            WorkflowDefinition.ref(id,revision);WorkflowDefinition.checkDigest(digest);WorkflowDefinition.checkDigest(batchDigest);
            if(expectedUpdatedAt.getEpochSecond()<0||!acknowledgeUncertainOutput)throw new IllegalArgumentException();
        }
        public String commandDigest(){return WorkflowDefinition.hash(List.of("workflow-abandon-sample-v1",id,Integer.toString(revision),digest,kind.name(),batchId.toString(),batchDigest,expectedUpdatedAt.toString(),"true"));}
    }
    public record Receipt(String schemaVersion,UUID requestId,String commandDigest,WorkflowQuality.Reference reference,
                          Kind kind,UUID batchId,String batchDigest,Instant proofUpdatedAt,Instant acceptedAt,
                          String state,int uncertainRecords) {
        public Receipt {
            Objects.requireNonNull(requestId);Objects.requireNonNull(reference);Objects.requireNonNull(kind);Objects.requireNonNull(batchId);Objects.requireNonNull(proofUpdatedAt);Objects.requireNonNull(acceptedAt);
            WorkflowDefinition.checkDigest(commandDigest);WorkflowDefinition.checkDigest(batchDigest);
            if(!"2.0".equals(schemaVersion)||!"ABANDONED".equals(state)||proofUpdatedAt.getEpochSecond()<0||acceptedAt.isBefore(proofUpdatedAt)||uncertainRecords<1||uncertainRecords>5
                ||!commandDigest.equals(new Command(requestId,reference.id(),reference.revision(),reference.digest(),kind,batchId,batchDigest,proofUpdatedAt,true).commandDigest()))throw new IllegalArgumentException();
        }
        public void require(Command c){if(!requestId.equals(c.requestId())||!commandDigest.equals(c.commandDigest())||!reference.matches(c.id(),c.revision(),c.digest())||kind!=c.kind()||!batchId.equals(c.batchId())||!batchDigest.equals(c.batchDigest())||!proofUpdatedAt.equals(c.expectedUpdatedAt()))throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);}
    }
    public record Status(String schemaVersion,Receipt closure){public Status{if(!"2.0".equals(schemaVersion))throw new IllegalArgumentException();}}
}
