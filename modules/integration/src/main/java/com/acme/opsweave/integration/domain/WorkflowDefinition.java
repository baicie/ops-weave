package com.acme.opsweave.integration.domain;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.*;

/** Bounded v2 transform chain. No arbitrary code, network or inventory-write nodes. */
public record WorkflowDefinition(String id, int revision, String name, Source source, Target target, List<Node> nodes, List<Edge> edges) {
    public enum Type { SOURCE, MAP, TRIM, EMPTY_TO_NULL, DEFAULT, ENUM_MAP, SCALE, FILTER, VALIDATE, OUTPUT }
    public record Source(String kind, String instanceId) {
        public Source { if (!Set.of("MANUAL_SAMPLE", "ZABBIX_HOST").contains(kind) || instanceId == null || !instanceId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") || kind.equals("MANUAL_SAMPLE") && !instanceId.equals("manual")) invalid(); }
    }
    public record Target(String id, int revision, String digest) {
        public Target { new ModelDefinition.Ref(id, revision); checkDigest(digest); }
        public ModelDefinition.Ref ref() { return new ModelDefinition.Ref(id, revision); }
    }
    public record Edge(String from, String to) { public Edge { nodeId(from); nodeId(to); } }
    public record Node(String id, Type type, String version, Map<String, String> config) {
        public Node {
            nodeId(id); Objects.requireNonNull(type); if (!"1".equals(version)) invalid(); config = Map.copyOf(config);
            if (config.size() > 32 || config.values().stream().anyMatch(v -> v.length() > 512)) invalid();
            switch (type) {
                case SOURCE, TRIM, VALIDATE, OUTPUT -> keys(config, Set.of());
                case MAP -> { if (config.isEmpty() || new HashSet<>(config.values()).size() != config.size()) invalid(); config.forEach((from,to) -> { field(from); field(to); }); }
                case EMPTY_TO_NULL -> { keys(config, Set.of("field")); field(config.get("field")); }
                case DEFAULT -> { keys(config, Set.of("field", "value")); field(config.get("field")); }
                case ENUM_MAP -> { keys(config, Set.of("field", "from", "to")); field(config.get("field")); }
                case SCALE -> { keys(config, Set.of("field", "factor")); field(config.get("field")); var f = decimal(config.get("factor")); if (f.abs().compareTo(new BigDecimal("1000000")) > 0 || f.signum() == 0) invalid(); }
                case FILTER -> { keys(config, Set.of("field", "equals")); field(config.get("field")); }
            }
        }
    }
    public WorkflowDefinition {
        ref(id, revision); if (name == null || name.isBlank() || name.length() > 80) invalid();
        Objects.requireNonNull(source); Objects.requireNonNull(target); nodes = List.copyOf(nodes); edges = List.copyOf(edges);
        if (nodes.size() < 4 || nodes.size() > 16 || edges.size() != nodes.size() - 1 || nodes.stream().map(Node::id).distinct().count() != nodes.size()) invalid();
        // The list is canonical execution order. Edges must express precisely that chain.
        if (nodes.getFirst().type != Type.SOURCE || nodes.get(1).type != Type.MAP || nodes.get(nodes.size()-2).type != Type.VALIDATE || nodes.getLast().type != Type.OUTPUT) invalid();
        for (int i=0;i<nodes.size();i++) {
            if (i >= 2 && i < nodes.size()-2 && Set.of(Type.SOURCE,Type.MAP,Type.VALIDATE,Type.OUTPUT).contains(nodes.get(i).type)) invalid();
            if (i < edges.size() && !edges.get(i).equals(new Edge(nodes.get(i).id, nodes.get(i+1).id))) invalid();
        }
    }
    public void requireModel(ModelDefinition model) {
        if (model.kind() != ModelDefinition.Kind.ENTITY || !model.ref().equals(target.ref()) || !model.digest().equals(target.digest)) throw new WorkflowFailure(WorkflowFailure.Code.MODEL_CHANGED);
        var fields = model.fields().stream().map(ModelDefinition.Field::id).collect(java.util.stream.Collectors.toSet());
        if (!fields.containsAll(nodes.get(1).config.values())) invalid();
        for (Node n : nodes) if (n.config.containsKey("field") && !fields.contains(n.config.get("field"))) invalid();
    }
    public String digest() {
        var parts = new ArrayList<String>(List.of("opsweave-transform-v2",id,Integer.toString(revision),name,source.kind,source.instanceId,target.id,Integer.toString(target.revision),target.digest));
        for (Node n : nodes) { parts.addAll(List.of(n.id,n.type.name(),n.version,Integer.toString(n.config.size()))); n.config.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> parts.addAll(List.of(e.getKey(),e.getValue()))); }
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
