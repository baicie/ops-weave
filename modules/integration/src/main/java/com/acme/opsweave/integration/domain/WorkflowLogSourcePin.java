package com.acme.opsweave.integration.domain;

import java.util.*;

/** Fixed discovered metadata, never an authorization or an assertion of current availability. */
public record WorkflowLogSourcePin(UUID inspectionId,String itemId,String hostId,String sourceKey,
        String sourceUnit,String sourceValueType,String digest) {
    public WorkflowLogSourcePin {
        Objects.requireNonNull(inspectionId);
        SourceMetricDiscovery.numericId(itemId);SourceMetricDiscovery.numericId(hostId);
        if(sourceKey==null||sourceKey.isBlank()||sourceKey.length()>2048||sourceUnit==null||sourceUnit.length()>64
            ||sourceKey.chars().anyMatch(Character::isISOControl)||sourceUnit.chars().anyMatch(Character::isISOControl)
            ||!"LOG".equals(sourceValueType)||!Objects.equals(digest,digest(inspectionId,itemId,hostId,sourceKey,sourceUnit,sourceValueType)))
            throw new IllegalArgumentException("Invalid workflow log source pin");
    }
    public static WorkflowLogSourcePin from(UUID inspection,SourceMetricDiscovery.Item item){
        return new WorkflowLogSourcePin(inspection,item.itemId(),item.hostId(),item.sourceKey(),item.sourceUnit(),item.sourceValueType(),
            digest(inspection,item.itemId(),item.hostId(),item.sourceKey(),item.sourceUnit(),item.sourceValueType()));
    }
    public static String digest(UUID inspection,String item,String host,String key,String unit,String type){
        return WorkflowDefinition.hash(List.of("workflow-log-source-v1",inspection.toString(),item,host,key,unit,type));
    }
}
