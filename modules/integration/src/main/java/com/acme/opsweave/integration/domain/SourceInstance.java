package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Maintained configuration metadata; immutable onboarding receipts and published workflows remain separate. */
public record SourceInstance(UUID id, String name, String description, WorkflowDefinition.Source source,
                             int configurationRevision, String connectionDigest, String dataMode,
                             int editVersion, String state, Instant createdAt, Instant updatedAt) {
    public SourceInstance {
        Objects.requireNonNull(id); Objects.requireNonNull(source); if(source.configuration()!=null)throw new IllegalArgumentException("Instance source cannot contain a workflow pin"); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if (name==null || name.isBlank() || name.length()>80 || !name.equals(name.trim()) || description==null || description.length()>500
            || configurationRevision<1 || configurationRevision>100 || editVersion<1 || editVersion>1000 || configurationRevision>editVersion
            || !Set.of("ACTIVE","ARCHIVED").contains(state) || updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("Invalid source instance");
        WorkflowDefinition.checkDigest(connectionDigest);
        if (!Set.of("fixture","zabbix-jsonrpc","MANUAL_SAMPLE").contains(dataMode)
            || source.kind().equals("MANUAL_SAMPLE")!=dataMode.equals("MANUAL_SAMPLE")) throw new IllegalArgumentException("Invalid source mode");
    }
    public String workflowId() { return "source-"+id; }
    public static SourceInstance initial(SourceSetup setup) {
        return new SourceInstance(setup.id(),setup.name().trim(),setup.description(),setup.source(),1,setup.connectionDigest(),setup.dataMode(),1,"ACTIVE",setup.createdAt(),setup.createdAt());
    }
    public record Configuration(UUID sourceId, int revision, String connectionDigest, String dataMode, Instant createdAt) {
        public Configuration { Objects.requireNonNull(sourceId); Objects.requireNonNull(createdAt); WorkflowDefinition.checkDigest(connectionDigest);
            if(revision<1 || revision>100 || !Set.of("fixture","zabbix-jsonrpc","MANUAL_SAMPLE").contains(dataMode))throw new IllegalArgumentException("Invalid source configuration"); }
    }
    public Configuration configuration() { return new Configuration(id,configurationRevision,connectionDigest,dataMode,updatedAt); }
    public record CommandReceipt(UUID requestId, UUID sourceId, String commandDigest, SourceInstance instance) {
        public CommandReceipt { Objects.requireNonNull(requestId); Objects.requireNonNull(sourceId); Objects.requireNonNull(instance); WorkflowDefinition.checkDigest(commandDigest);
            if(!sourceId.equals(instance.id()))throw new IllegalArgumentException("Invalid source command receipt"); }
    }
}
