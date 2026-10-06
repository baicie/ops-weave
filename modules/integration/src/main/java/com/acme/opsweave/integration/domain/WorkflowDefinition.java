package com.acme.opsweave.integration.domain;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.*;

/** Bounded v2 transform DAG. No arbitrary code or network nodes. */
public record WorkflowDefinition(String id, int revision, String name, Source source, Target target, List<Node> nodes, List<Edge> edges) {
    public enum Type { SOURCE, MAP, TRIM, EMPTY_TO_NULL, DEFAULT, ENUM_MAP, SCALE, FILTER, MERGE, VALIDATE, OUTPUT }
    public record ConfigurationPin(UUID sourceId, int revision, String digest) {
        public ConfigurationPin { Objects.requireNonNull(sourceId); if(revision<1||revision>100)invalid(); checkDigest(digest); }
    }
    public record Source(String kind, String instanceId, ConfigurationPin configuration, WorkflowMetricSourcePin metric, WorkflowLogSourcePin log) {
        public Source(String kind,String instanceId,ConfigurationPin configuration,WorkflowMetricSourcePin metric){this(kind,instanceId,configuration,metric,null);}
        public Source(String kind,String instanceId,ConfigurationPin configuration){this(kind,instanceId,configuration,null);}
        public Source(String kind,String instanceId){this(kind,instanceId,null);}
        public Source { if (!Set.of("MANUAL_SAMPLE", "ZABBIX_HOST", "ZABBIX_METRIC", "ZABBIX_LOG").contains(kind) || instanceId == null || !instanceId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") || kind.equals("MANUAL_SAMPLE") && (!instanceId.equals("manual")||configuration!=null) || (kind.equals("ZABBIX_METRIC") ? configuration==null||metric==null : metric!=null) || (kind.equals("ZABBIX_LOG") ? configuration==null||log==null : log!=null)) invalid(); }
    }
    public record Target(String id, int revision, String digest, String kind, com.acme.opsweave.telemetry.domain.MetricMappingPin mappingPin,String metricKey) {
        public Target(String id,int revision,String digest){this(id,revision,digest,"ENTITY",null,null);}
        public Target(String id,int revision,String digest,String kind){this(id,revision,digest,kind,null,null);}
        public Target { if("ENTITY".equals(kind)){new ModelDefinition.Ref(id, revision);checkDigest(digest);if(mappingPin!=null||metricKey!=null)invalid();}else if(!Set.of("LOG","METRIC").contains(kind)||id!=null||digest!=null||revision!=1||mappingPin!=null&&!kind.equals("METRIC")||mappingPin==null&&metricKey!=null||mappingPin!=null&&(metricKey==null||!metricKey.matches("[A-Za-z][A-Za-z0-9_.:/-]{0,127}")))invalid(); }
        public boolean entity(){return kind.equals("ENTITY");}
        public ModelDefinition.Ref ref() { if(!entity())invalid();return new ModelDefinition.Ref(id, revision); }
    }
    public record Edge(String from, String to) { public Edge { nodeId(from); nodeId(to); } }
    public record Node(String id, Type type, String version, Map<String, String> config, String operatorDigest) {
        public Node(String id, Type type, String version, Map<String, String> config) { this(id,type,version,config,null); }
        public Node {
            nodeId(id); Objects.requireNonNull(type); if (!"1".equals(version)) invalid(); config = Map.copyOf(config);
            if (operatorDigest != null) checkDigest(operatorDigest);
            WorkflowOperators.configuration(type,version,config);
        }
    }
    public WorkflowDefinition {
        ref(id, revision); if (name == null || name.isBlank() || name.length() > 80) invalid();
        Objects.requireNonNull(source); Objects.requireNonNull(target); nodes = List.copyOf(nodes); edges = List.copyOf(edges);
        if (nodes.size() < 4 || nodes.size() > 16 || edges.size() < nodes.size() - 1 || edges.size() > 32 || nodes.stream().map(Node::id).distinct().count() != nodes.size() || new HashSet<>(edges).size()!=edges.size()) invalid();
        // Stored nodes are a topological execution order; existing chain definitions remain valid.
        if (nodes.getFirst().type != Type.SOURCE || nodes.get(1).type != Type.MAP || nodes.get(nodes.size()-2).type != Type.VALIDATE || nodes.getLast().type != Type.OUTPUT) invalid();
        var indexes=new HashMap<String,Integer>();for(int i=0;i<nodes.size();i++)indexes.put(nodes.get(i).id,i);
        var incoming=new int[nodes.size()];var outgoing=new int[nodes.size()];
        for(var edge:edges){Integer from=indexes.get(edge.from),to=indexes.get(edge.to);if(from==null||to==null||from>=to)invalid();incoming[to]++;outgoing[from]++;}
        if(outgoing[0]!=1||incoming[1]!=1||!edges.contains(new Edge(nodes.getFirst().id,nodes.get(1).id))||outgoing[nodes.size()-2]!=1||incoming[nodes.size()-1]!=1||!edges.contains(new Edge(nodes.get(nodes.size()-2).id,nodes.getLast().id)))invalid();
        for (int i=0;i<nodes.size();i++) {
            if (i >= 2 && i < nodes.size()-2 && Set.of(Type.SOURCE,Type.MAP,Type.VALIDATE,Type.OUTPUT).contains(nodes.get(i).type)) invalid();
            if(i>0&&(incoming[i]<1||nodes.get(i).type!=Type.MERGE&&incoming[i]!=1)||i<nodes.size()-1&&outgoing[i]<1)invalid();
        }
        if(source.kind.equals("ZABBIX_METRIC")&&target.mappingPin==null)invalid();
        if(source.kind.equals("ZABBIX_LOG")&&!target.kind.equals("LOG"))invalid();
        if(!target.entity()){if(source.kind.equals("ZABBIX_HOST"))invalid();requireFields(nodes,TelemetryOutput.fields(target));}
    }
    public void requireModel(ModelDefinition model) {
        if (!target.entity() || model==null || model.kind() != ModelDefinition.Kind.ENTITY || !model.ref().equals(target.ref()) || !model.digest().equals(target.digest)) throw new WorkflowFailure(WorkflowFailure.Code.MODEL_CHANGED);
        var fields = model.fields().stream().map(ModelDefinition.Field::id).collect(java.util.stream.Collectors.toSet());
        requireFields(nodes,fields);
    }
    public void requireOutput(ModelDefinition model){if(target.entity())requireModel(model);else requireFields(nodes,TelemetryOutput.fields(target));}
    private static void requireFields(List<Node> nodes,Set<String> fields){if(!fields.containsAll(nodes.get(1).config.values()))invalid();for(Node n:nodes)if(n.config.containsKey("field")&&!fields.contains(n.config.get("field")))invalid();}
    public String digest() {
        var parts = new ArrayList<String>(List.of("opsweave-transform-v2",id,Integer.toString(revision),name,source.kind,source.instanceId));
        if(source.configuration!=null)parts.addAll(List.of("fixed-source-configuration-v2",source.configuration.sourceId.toString(),Integer.toString(source.configuration.revision),source.configuration.digest));
        if(source.metric!=null)parts.addAll(List.of("fixed-metric-source-v1",source.metric.inspectionId().toString(),source.metric.itemId(),source.metric.hostId(),source.metric.sourceKey(),source.metric.sourceUnit(),source.metric.sourceValueType(),source.metric.digest()));
        if(source.log!=null)parts.addAll(List.of("fixed-log-source-v1",source.log.inspectionId().toString(),source.log.itemId(),source.log.hostId(),source.log.sourceKey(),source.log.sourceUnit(),source.log.sourceValueType(),source.log.digest()));
        if(target.entity())parts.addAll(List.of(target.id,Integer.toString(target.revision),target.digest));else parts.addAll(List.of("telemetry-output",target.kind,target.mappingPin==null?"1.0":"1.1"));
        if(target.mappingPin!=null)parts.addAll(List.of("standard-metric-output-v2",target.metricKey,target.mappingPin.id(),Integer.toString(target.mappingPin.revision()),target.mappingPin.digest()));
        for (Node n : nodes) { parts.addAll(List.of(n.id,n.type.name(),n.version,Integer.toString(n.config.size()))); if(n.operatorDigest!=null)parts.addAll(List.of("operator-pin-v2",n.operatorDigest)); n.config.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> parts.addAll(List.of(e.getKey(),e.getValue()))); }
        for (Edge e : edges) parts.addAll(List.of(e.from,e.to));
        return hash(parts);
    }
    public static String hash(List<String> parts) { try { var md=MessageDigest.getInstance("SHA-256"); for (String p:parts) { byte[] b=p.getBytes(StandardCharsets.UTF_8); md.update((b.length+":").getBytes(StandardCharsets.US_ASCII)); md.update(b); } return "sha256:"+HexFormat.of().formatHex(md.digest()); } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(); } }
    public static void ref(String id,int revision) { if(id==null || !id.matches("[a-z][a-z0-9_-]{0,47}") || revision<1 || revision>10000) invalid(); }
    public static void checkDigest(String digest) { if(digest==null || !digest.matches("sha256:[a-f0-9]{64}")) invalid(); }
    public static void field(String value) { if(value==null || !value.matches("[a-zA-Z][a-zA-Z0-9_]{0,47}") || Set.of("tenantId","tenant_id","Authorization","authorization","constructor","prototype","secret","token","password").contains(value)) invalid(); }
    private static void nodeId(String id) { if(id==null || !id.matches("[a-z][a-z0-9_-]{0,31}")) invalid(); }
    private static void keys(Map<String,String> config,Set<String> allowed) { if(!config.keySet().equals(allowed)) invalid(); }
    public static BigDecimal decimal(String s) { if(s==null || s.length()>64 || !s.strip().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw new IllegalArgumentException("Invalid decimal"); var n=new BigDecimal(s.strip()).stripTrailingZeros(); if(n.abs().compareTo(ModelDefinition.MAX_NUMBER)>0 || n.scale()>12) invalid(); return n; }
    private static void invalid() { throw new IllegalArgumentException("Invalid workflow definition"); }
}
