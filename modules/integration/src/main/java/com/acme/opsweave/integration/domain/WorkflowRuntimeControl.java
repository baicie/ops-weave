package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Immutable acknowledgement of an admitted control command; identity is the store's trusted scope. */
public final class WorkflowRuntimeControl {
    private WorkflowRuntimeControl() {}
    public enum Operation { START, STOP, RESUME }
    public record Command(UUID requestId,String id,int revision,String digest,WorkflowRuntime.Settings settings,long expectedGeneration,Operation operation) {
        public Command {
            Objects.requireNonNull(requestId);Objects.requireNonNull(settings);Objects.requireNonNull(operation);
            WorkflowDefinition.ref(id,revision);WorkflowDefinition.checkDigest(digest);
            if(expectedGeneration<0 || expectedGeneration>1_000_000)throw new IllegalArgumentException("Invalid control generation");
        }
        public String commandDigest() {return WorkflowDefinition.hash(List.of("workflow-runtime-control-v1",operation.name(),id,Integer.toString(revision),digest,settings.identityField(),settings.nameField(),Long.toString(expectedGeneration)));}
    }
    public record Receipt(UUID requestId,Operation operation,String commandDigest,Instant createdAt,WorkflowRuntime.Task task) {
        public Receipt {
            Objects.requireNonNull(requestId);Objects.requireNonNull(operation);Objects.requireNonNull(createdAt);Objects.requireNonNull(task);
            WorkflowDefinition.checkDigest(commandDigest);
            if(!createdAt.equals(task.updatedAt()) || !task.state().equals(operation==Operation.STOP?"STOPPED":"RUNNING"))throw new IllegalArgumentException("Invalid control receipt");
        }
        public void require(Command command) {
            if(!requestId.equals(command.requestId()) || operation!=command.operation() || !commandDigest.equals(command.commandDigest())
                || !task.workflowId().equals(command.id()) || task.revision()!=command.revision() || !task.digest().equals(command.digest())
                || !task.settings().equals(command.settings()) || task.generation()!=command.expectedGeneration()+1)
                throw new WorkflowFailure(WorkflowFailure.Code.CONFLICT);
        }
    }
}
