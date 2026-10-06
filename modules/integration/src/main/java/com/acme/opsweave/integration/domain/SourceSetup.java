package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Immutable onboarding receipt. It is not an enabled collector or an active workflow binding. */
public record SourceSetup(UUID id, String name, String description, WorkflowDefinition.Source source,
                          String connectionDigest, String dataMode, WorkflowDefinition.Target initialTarget,
                          String digest, Instant createdAt) {
    public SourceSetup {
        Objects.requireNonNull(id); Objects.requireNonNull(source); if(source.configuration()!=null)throw new IllegalArgumentException("Instance source cannot contain a workflow pin");
        if(initialTarget!=null && !initialTarget.entity())throw new IllegalArgumentException("Legacy setup target must be an entity");
        if(name==null || name.isBlank() || name.length()>80 || description==null || description.length()>500) throw new IllegalArgumentException("Invalid source setup");
        if(!Set.of("fixture","zabbix-jsonrpc","MANUAL_SAMPLE").contains(dataMode)||source.kind().equals("MANUAL_SAMPLE")!=dataMode.equals("MANUAL_SAMPLE"))throw new IllegalArgumentException("Invalid source mode");
        WorkflowDefinition.checkDigest(connectionDigest); Objects.requireNonNull(createdAt);
        if(!fingerprint(id,name,description,source,connectionDigest,dataMode,initialTarget).equals(digest)) throw new IllegalArgumentException("Invalid source setup digest");
    }
    public String workflowId() { return "source-"+id; }
    public static String fingerprint(UUID id,String name,String description,WorkflowDefinition.Source source,String connectionDigest,String dataMode,WorkflowDefinition.Target target) {
        if(target==null)return WorkflowDefinition.hash(List.of("source-setup-source-only-v1",id.toString(),name,description,source.kind(),source.instanceId(),connectionDigest,dataMode));
        return WorkflowDefinition.hash(List.of("source-setup-v1",id.toString(),name,description,source.kind(),source.instanceId(),connectionDigest,dataMode,target.id(),Integer.toString(target.revision()),target.digest()));
    }
}
