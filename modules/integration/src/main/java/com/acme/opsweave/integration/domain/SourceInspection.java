package com.acme.opsweave.integration.domain;

import java.time.*;
import java.util.*;

/** Bounded metadata from an explicit source check or discovery. No source values or credentials. */
public record SourceInspection(UUID requestId, UUID sourceId, String kind, int configurationRevision,
        String connectionDigest, String dataMode, String commandDigest, String state,
        Instant asOf, Instant deadline, Instant availableAt, Instant expiresAt, Check check, Discovery discovery, SourceMetricDiscovery metricDiscovery, UUID previousRequestId, SourceMetricPage metricPage) {
    public SourceInspection(UUID requestId, UUID sourceId, String kind, int configurationRevision, String connectionDigest,
            String dataMode, String commandDigest, String state, Instant asOf, Instant deadline, Instant availableAt,
            Instant expiresAt, Check check, Discovery discovery, SourceMetricDiscovery metricDiscovery) {
        this(requestId,sourceId,kind,configurationRevision,connectionDigest,dataMode,commandDigest,state,asOf,deadline,availableAt,expiresAt,check,discovery,metricDiscovery,null,null);
    }
    public SourceInspection(UUID requestId, UUID sourceId, String kind, int configurationRevision, String connectionDigest,
            String dataMode, String commandDigest, String state, Instant asOf, Instant deadline, Instant availableAt,
            Instant expiresAt, Check check, Discovery discovery) {
        this(requestId,sourceId,kind,configurationRevision,connectionDigest,dataMode,commandDigest,state,asOf,deadline,availableAt,expiresAt,check,discovery,null);
    }
    public static final Duration TIMEOUT = Duration.ofSeconds(65), VALID_FOR = Duration.ofMinutes(15);
    public SourceInspection {
        Objects.requireNonNull(requestId); Objects.requireNonNull(sourceId); Objects.requireNonNull(asOf); Objects.requireNonNull(deadline);
        WorkflowDefinition.checkDigest(connectionDigest); WorkflowDefinition.checkDigest(commandDigest);
        if(!Set.of("TEST","DISCOVER","DISCOVER_METRICS","DISCOVER_METRIC_PAGE").contains(kind) || configurationRevision<1 || configurationRevision>100
            || !Set.of("fixture","zabbix-jsonrpc").contains(dataMode) || !Set.of("PENDING","COMPLETED","UNKNOWN").contains(state)
            || !deadline.equals(asOf.plus(TIMEOUT)) || !commandDigest.equals(commandDigest(sourceId,requestId,kind,configurationRevision,connectionDigest,previousRequestId)))throw new IllegalArgumentException("Invalid source inspection");
        if (!kind.equals("DISCOVER_METRIC_PAGE") && (previousRequestId != null || metricPage != null) || requestId.equals(previousRequestId)) throw new IllegalArgumentException("Invalid metric page lineage");
        if(state.equals("COMPLETED")) {
            if(availableAt==null || expiresAt==null || availableAt.isBefore(asOf) || availableAt.isAfter(deadline)
                || !expiresAt.equals(availableAt.plus(VALID_FOR)) || switch(kind) {
                    case "TEST" -> check==null||discovery!=null||metricDiscovery!=null||metricPage!=null;
                    case "DISCOVER" -> discovery==null||check!=null||metricDiscovery!=null||metricPage!=null;
                    case "DISCOVER_METRICS" -> metricDiscovery==null||check!=null||discovery!=null||metricPage!=null;
                    default -> metricPage==null||check!=null||discovery!=null||metricDiscovery!=null;
                })throw new IllegalArgumentException("Invalid inspection result");
            if(metricPage!=null&&metricPage.manifest()!=null){var m=metricPage.manifest();
                if(m.asOf().isAfter(asOf)||!availableAt.isBefore(m.expiresAt())
                    ||previousRequestId==null&&(!m.snapshotId().equals(requestId)||!m.asOf().equals(asOf)||metricPage.offset()!=0)
                    ||previousRequestId!=null&&(m.snapshotId().equals(requestId)||metricPage.offset()==0))
                    throw new IllegalArgumentException("Invalid metric page temporal lineage");
            }
        } else if(availableAt!=null || expiresAt!=null || check!=null || discovery!=null||metricDiscovery!=null||metricPage!=null)throw new IllegalArgumentException("Unconfirmed inspection cannot carry a result");
    }
    public record Check(boolean reachable,String statusCode,String reportedVersion) {
        public Check { if(!Set.of("READ_VERIFIED","UNREACHABLE","UNVERIFIED","LABELED_FIXTURE").contains(statusCode)
            || reachable==statusCode.equals("UNREACHABLE") || reportedVersion!=null&&!reportedVersion.matches("[ -~]{1,32}"))throw new IllegalArgumentException("Invalid inspection check"); }
    }
    public record Field(String name,String type,boolean nullable) {
        public Field { if(!Set.of("hostid","host","name","status","interfaces.ip").contains(name)
            || !Set.of("TEXT","NUMBER","BOOLEAN","TEXT_ARRAY","NULL","MIXED").contains(type))throw new IllegalArgumentException("Invalid discovered field"); }
    }
    public record Discovery(List<Field> fields,int observedRecords,boolean complete,String scanConsistency,String statusCode,String fingerprint) {
        public Discovery { fields=List.copyOf(fields); WorkflowDefinition.checkDigest(fingerprint);
            if(fields.size()>5 || fields.stream().map(Field::name).distinct().count()!=fields.size() || observedRecords<0 || observedRecords>5
                || !Set.of("HOSTID_WATERMARK","LABELED_FIXTURE","UNVERIFIED").contains(scanConsistency)
                || !Set.of("READ_VERIFIED","INCOMPLETE","UNREACHABLE").contains(statusCode)
                || complete && (scanConsistency.equals("UNVERIFIED") || !statusCode.equals("READ_VERIFIED"))
                || statusCode.equals("UNREACHABLE") && (!fields.isEmpty()||observedRecords!=0||complete)
                || observedRecords==0&&!fields.isEmpty() || !fingerprint.equals(fieldDigest(fields)))throw new IllegalArgumentException("Invalid discovery snapshot"); }
        public String scope() { return "FIRST_HOST_PAGE"; }
    }
    public static String fieldDigest(List<Field> fields) {
        var parts=new ArrayList<String>();parts.add("source-host-fields-v1");
        fields.stream().sorted(Comparator.comparing(Field::name)).forEach(f->{parts.add(f.name());parts.add(f.type());parts.add(Boolean.toString(f.nullable()));});
        return WorkflowDefinition.hash(parts);
    }
    public static String commandDigest(UUID source,UUID request,String kind,int revision,String digest) {return commandDigest(source,request,kind,revision,digest,null);}
    public static String commandDigest(UUID source,UUID request,String kind,int revision,String digest,UUID previous) {
        return kind.equals("DISCOVER_METRIC_PAGE") ? WorkflowDefinition.hash(List.of("source-metric-page-command-v1",source.toString(),request.toString(),Integer.toString(revision),digest,previous==null?"initial":previous.toString()))
            : WorkflowDefinition.hash(List.of("source-inspection-v2",source.toString(),request.toString(),kind,Integer.toString(revision),digest));
    }
    public static SourceInspection pending(UUID source,UUID request,String kind,SourceInstance i,Instant now) {return pending(source,request,kind,i,now,null);}
    public static SourceInspection pending(UUID source,UUID request,String kind,SourceInstance i,Instant now,UUID previous) {
        return new SourceInspection(request,source,kind,i.configurationRevision(),i.connectionDigest(),i.dataMode(),commandDigest(source,request,kind,i.configurationRevision(),i.connectionDigest(),previous),"PENDING",now,now.plus(TIMEOUT),null,null,null,null,null,previous,null);
    }
    public SourceInspection unknown() {return new SourceInspection(requestId,sourceId,kind,configurationRevision,connectionDigest,dataMode,commandDigest,"UNKNOWN",asOf,deadline,null,null,null,null,null,previousRequestId,null);}
    public SourceInspection complete(Instant now,Check c,Discovery d) {return complete(now,c,d,null,null);}
    public SourceInspection complete(Instant now,Check c,Discovery d,SourceMetricDiscovery m) {return complete(now,c,d,m,null);}
    public SourceInspection complete(Instant now,Check c,Discovery d,SourceMetricDiscovery m,SourceMetricPage page) {return new SourceInspection(requestId,sourceId,kind,configurationRevision,connectionDigest,dataMode,commandDigest,"COMPLETED",asOf,deadline,now,now.plus(VALID_FOR),c,d,m,previousRequestId,page);}
}
