package com.acme.opsweave.integration.domain;

import java.util.*;

/** Immutable metadata provenance. This pin is never an authorization or a claim of freshness. */
public record WorkflowMetricSourcePin(UUID inspectionId, String itemId, String hostId, String sourceKey,
        String sourceUnit, String sourceValueType, String digest) {
    public WorkflowMetricSourcePin {
        Objects.requireNonNull(inspectionId);
        SourceMetricDiscovery.numericId(itemId); SourceMetricDiscovery.numericId(hostId);
        if (sourceKey == null || sourceKey.isBlank() || sourceKey.length() > 2048
            || sourceUnit == null || sourceUnit.length() > 64
            || sourceKey.chars().anyMatch(Character::isISOControl) || sourceUnit.chars().anyMatch(Character::isISOControl)
            || !Set.of("FLOAT", "UNSIGNED").contains(sourceValueType)
            || !Objects.equals(digest, digest(inspectionId,itemId,hostId,sourceKey,sourceUnit,sourceValueType)))
            throw new IllegalArgumentException("Invalid workflow metric source pin");
    }
    public static WorkflowMetricSourcePin from(UUID inspection, SourceMetricDiscovery.Item item) {
        return new WorkflowMetricSourcePin(inspection,item.itemId(),item.hostId(),item.sourceKey(),item.sourceUnit(),item.sourceValueType(),
            digest(inspection,item.itemId(),item.hostId(),item.sourceKey(),item.sourceUnit(),item.sourceValueType()));
    }
    public static String digest(UUID inspection,String item,String host,String key,String unit,String type) {
        return WorkflowDefinition.hash(List.of("workflow-metric-source-v1",inspection.toString(),item,host,key,unit,type));
    }
}
