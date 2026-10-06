package com.acme.opsweave.integration.domain;

import java.time.Instant;
import java.util.*;

/** Immutable non-secret connector snapshot. Names do not change connection semantics. */
public record SourceConnectionConfiguration(UUID sourceId,int revision,String connectorVersion,SourceEndpoint endpoint,
                                            SourceCredential.Pin credentialPin,List<String> hostGroupIds,String connectionDigest,Instant createdAt) {
    public static final String LEGACY_VERSION="host-jsonrpc-v1";
    public static final String VERSION="host-jsonrpc-v2";
    public static final int MAX_HOST_GROUPS=32;
    private static final java.util.regex.Pattern HOST_GROUP_ID=java.util.regex.Pattern.compile("[1-9][0-9]{0,18}");
    public SourceConnectionConfiguration {
        Objects.requireNonNull(sourceId);Objects.requireNonNull(endpoint);Objects.requireNonNull(credentialPin);Objects.requireNonNull(createdAt);
        hostGroupIds=normalizeHostGroupIds(hostGroupIds);
        boolean legacy=LEGACY_VERSION.equals(connectorVersion)&&hostGroupIds.isEmpty()&&legacyFingerprint(sourceId,endpoint.pin(),credentialPin).equals(connectionDigest);
        boolean scoped=VERSION.equals(connectorVersion)&&!hostGroupIds.isEmpty()&&fingerprint(sourceId,endpoint.pin(),credentialPin,hostGroupIds).equals(connectionDigest);
        if(revision<1||revision>100||!legacy&&!scoped)throw new IllegalArgumentException("Invalid source connection configuration");
    }
    public SourceConnectionConfiguration(UUID sourceId,int revision,String connectorVersion,SourceEndpoint endpoint,SourceCredential.Pin credentialPin,String connectionDigest,Instant createdAt){
        this(sourceId,revision,connectorVersion,endpoint,credentialPin,List.of(),connectionDigest,createdAt);
    }
    public static String physicalId(UUID sourceId){return "connection-"+sourceId;}
    public static List<String> normalizeHostGroupIds(List<String> values){
        Objects.requireNonNull(values);if(values.size()>MAX_HOST_GROUPS)throw new IllegalArgumentException("Invalid host group scope");
        var unique=new HashSet<String>();for(String value:values)if(value==null||!HOST_GROUP_ID.matcher(value).matches()||!unique.add(value))throw new IllegalArgumentException("Invalid host group scope");
        return values.stream().sorted(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())).toList();
    }
    public static String legacyFingerprint(UUID sourceId,SourceEndpoint.Pin endpoint,SourceCredential.Pin credential){return WorkflowDefinition.hash(List.of("source-connection-v2",sourceId.toString(),LEGACY_VERSION,endpoint.id(),endpoint.digest(),credential.credentialId().toString(),Integer.toString(credential.revision()),credential.versionId().toString()));}
    public static String fingerprint(UUID sourceId,SourceEndpoint.Pin endpoint,SourceCredential.Pin credential){return legacyFingerprint(sourceId,endpoint,credential);}
    public static String fingerprint(UUID sourceId,SourceEndpoint.Pin endpoint,SourceCredential.Pin credential,List<String> groups){
        var ids=normalizeHostGroupIds(groups);if(ids.isEmpty())return legacyFingerprint(sourceId,endpoint,credential);
        var values=new ArrayList<String>(List.of("source-connection-v3",sourceId.toString(),VERSION,endpoint.id(),endpoint.digest(),credential.credentialId().toString(),Integer.toString(credential.revision()),credential.versionId().toString(),Integer.toString(ids.size())));values.addAll(ids);return WorkflowDefinition.hash(values);
    }
    public boolean scoped(){return VERSION.equals(connectorVersion)&&!hostGroupIds.isEmpty();}
    public static SourceConnectionConfiguration snapshot(UUID id,int revision,SourceEndpoint endpoint,SourceCredential.Pin credential,Instant at){return new SourceConnectionConfiguration(id,revision,LEGACY_VERSION,endpoint,credential,List.of(),legacyFingerprint(id,endpoint.pin(),credential),at);}
    public static SourceConnectionConfiguration snapshot(UUID id,int revision,SourceEndpoint endpoint,SourceCredential.Pin credential,List<String> groups,Instant at){var ids=normalizeHostGroupIds(groups);return new SourceConnectionConfiguration(id,revision,VERSION,endpoint,credential,ids,fingerprint(id,endpoint.pin(),credential,ids),at);}
    public static void requireLineage(SourceInstance instance,List<SourceInstance.Configuration> generic,List<SourceConnectionConfiguration> connections){
        if(connections.isEmpty()){if(instance.source().instanceId().equals(physicalId(instance.id())))throw new IllegalStateException("Source connection history unavailable");return;}
        int first=connections.stream().mapToInt(SourceConnectionConfiguration::revision).min().orElseThrow();
        if(instance.source().kind().equals("MANUAL_SAMPLE")||connections.size()!=instance.configurationRevision()-first+1
            ||connections.stream().map(SourceConnectionConfiguration::revision).distinct().count()!=connections.size()
            ||instance.source().instanceId().equals(physicalId(instance.id()))&&first!=1
            ||connections.stream().anyMatch(c->!c.sourceId().equals(instance.id())||c.revision()>instance.configurationRevision()||generic.stream().noneMatch(g->g.sourceId().equals(c.sourceId())&&g.revision()==c.revision()&&g.connectionDigest().equals(c.connectionDigest())&&g.dataMode().equals("zabbix-jsonrpc")&&g.createdAt().equals(c.createdAt()))))throw new IllegalStateException("Source connection history unavailable");
    }
}
