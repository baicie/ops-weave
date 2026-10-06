package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.Objects;

/** Immutable terminal metadata when an explicit START replaces a fixed task version. */
public record WorkflowTaskArchive(String schemaVersion, WorkflowQuality.Reference reference,
                                  WorkflowQuality.Kind kind, WorkflowQuality.Task task, WorkflowQuality.Task schedule,
                                  WorkflowQuality.Reference replacedBy, long nextGeneration, Instant replacedAt) {
    public WorkflowTaskArchive {
        Objects.requireNonNull(reference); Objects.requireNonNull(kind); Objects.requireNonNull(task);
        Objects.requireNonNull(replacedBy); Objects.requireNonNull(replacedAt);
        if (!"2.0".equals(schemaVersion) || task.state().equals("RUNNING")
                || !reference.id().equals(replacedBy.id()) || replacedBy.revision() <= reference.revision()
                || nextGeneration != task.generation() + 1 || nextGeneration > 999_999
                || replacedAt.isBefore(task.updatedAt())) throw new IllegalArgumentException("Invalid task archive");
        if(schedule!=null&&(kind!=WorkflowQuality.Kind.HOST_SCAN||schedule.state().equals("RUNNING")
                ||schedule.state().equals("ABANDONED")||replacedAt.isBefore(schedule.updatedAt())
                ||!Objects.equals(schedule.pendingBatchId(),task.pendingBatchId())))throw new IllegalArgumentException("Invalid archived schedule");
    }
}
