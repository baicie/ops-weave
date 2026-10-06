package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Explicitly closes recovery, without reclassifying uncertain output or advancing confirmed progress. */
public final class WorkflowRecovery {
    private WorkflowRecovery() {}
    public record VersionControl(long generation,boolean startAllowed,boolean recoveryClosed) {
        public VersionControl {if(generation<0||generation>1_000_001||startAllowed&&(generation>=999_999||recoveryClosed))throw new IllegalArgumentException();}
    }
    public record Command(UUID requestId,String id,int revision,String digest,WorkflowQuality.Kind kind,UUID batchId,long expectedGeneration,boolean acknowledgeUncertainOutput) {
        public Command {
            Objects.requireNonNull(requestId);Objects.requireNonNull(kind);Objects.requireNonNull(batchId);
            WorkflowDefinition.ref(id,revision);WorkflowDefinition.checkDigest(digest);
            if(expectedGeneration<1||expectedGeneration>1_000_000||!acknowledgeUncertainOutput)throw new IllegalArgumentException();
        }
        public String commandDigest(){return WorkflowDefinition.hash(List.of("workflow-abandon-recovery-v1",id,Integer.toString(revision),digest,kind.name(),batchId.toString(),Long.toString(expectedGeneration),"true"));}
    }
    public record Receipt(UUID requestId,String commandDigest,WorkflowQuality.Reference reference,WorkflowQuality.Kind kind,UUID batchId,
                          long previousGeneration,long generation,Instant acceptedAt,String state,String preservedCursor,int confirmedBatches,int confirmedRecords) {
        public Receipt {
            Objects.requireNonNull(requestId);Objects.requireNonNull(reference);Objects.requireNonNull(kind);Objects.requireNonNull(batchId);Objects.requireNonNull(acceptedAt);
            WorkflowDefinition.checkDigest(commandDigest);
            if(previousGeneration<1||previousGeneration>1_000_000||generation!=previousGeneration+1||!"ABANDONED".equals(state)
                ||confirmedBatches<0||confirmedBatches>200||confirmedRecords<0||confirmedRecords>confirmedBatches*(kind==WorkflowQuality.Kind.HOST_SCAN?5:kind==WorkflowQuality.Kind.METRIC_STREAM?600:1000))throw new IllegalArgumentException();
            if(kind==WorkflowQuality.Kind.HOST_SCAN&&preservedCursor!=null)throw new IllegalArgumentException();
            else if(kind!=WorkflowQuality.Kind.HOST_SCAN){var cursor=Instant.parse(Objects.requireNonNull(preservedCursor));if(cursor.getNano()!=0||cursor.getEpochSecond()<0||cursor.isAfter(acceptedAt)||!cursor.toString().equals(preservedCursor))throw new IllegalArgumentException();}
        }
        public void require(Command c){if(!requestId.equals(c.requestId())||!commandDigest.equals(c.commandDigest())||!reference.matches(c.id(),c.revision(),c.digest())||kind!=c.kind()||!batchId.equals(c.batchId())||previousGeneration!=c.expectedGeneration())throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);}
    }
}
