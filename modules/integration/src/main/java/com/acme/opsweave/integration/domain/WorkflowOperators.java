package com.acme.opsweave.integration.domain;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import java.math.BigDecimal;
import java.util.*;

/** Static, generated contract metadata. No loading of executable code or remote packages. */
public final class WorkflowOperators {
    public enum Category { STRUCTURE, TRANSFORM, ROUTING }
    public enum ConfigKind { EMPTY, FIELD_MAPPING, PARAMETERS }
    public enum ParameterKind { FIELD, TEXT, FACTOR }
    public enum PortKind { RECORD_V1, VALIDATED_RECORD_V1 }
    public record Parameter(String name, ParameterKind kind) {}
    public record Port(String id, PortKind kind, int minimum, int maximum) {}
    public record Descriptor(WorkflowDefinition.Type type, String version, String digest, String implementation,
                             String label, String hint, Category category, ConfigKind configKind,
                             List<Parameter> parameters, Port input, Port output) {
        public Descriptor { parameters = List.copyOf(parameters); }
    }
    private static final WorkflowOperators BUILTIN = new WorkflowOperators(GeneratedWorkflowOperators.descriptors());
    private final Map<WorkflowDefinition.Type, Descriptor> descriptors;

    private WorkflowOperators(List<Descriptor> values) {
        var result = new EnumMap<WorkflowDefinition.Type, Descriptor>(WorkflowDefinition.Type.class);
        for (var d : values) {
            if (result.put(d.type(), d) != null || !d.digest().equals(semanticDigest(d))) throw new IllegalStateException("Invalid built-in operator registry");
        }
        descriptors = Collections.unmodifiableMap(result);
        if (!catalogDigest().equals(GeneratedWorkflowOperators.DIGEST)) throw new IllegalStateException("Invalid built-in catalog digest");
    }
    public static WorkflowOperators builtIn() { return BUILTIN; }
    public List<Descriptor> descriptors() { return List.copyOf(descriptors.values()); }
    public String catalogVersion() { return GeneratedWorkflowOperators.VERSION; }
    public String catalogDigest() {
        var parts = new ArrayList<String>(List.of("opsweave-operator-catalog-v1", catalogVersion()));
        descriptors.values().stream().sorted(Comparator.comparing(d -> d.type().name())).forEach(d -> parts.addAll(List.of(d.type().name(), d.version(), d.digest())));
        return WorkflowDefinition.hash(parts);
    }
    public Descriptor descriptor(WorkflowDefinition.Type type, String version) {
        var d = descriptors.get(type);
        if (d == null || !d.version().equals(version)) throw new WorkflowFailure(WorkflowFailure.Code.OPERATOR_CHANGED);
        return d;
    }
    public WorkflowDefinition.Node pin(WorkflowDefinition.Node node) {
        var d = descriptor(node.type(), node.version());
        return new WorkflowDefinition.Node(node.id(), node.type(), node.version(), node.config(), d.digest());
    }
    public WorkflowDefinition pin(WorkflowDefinition definition) {
        return new WorkflowDefinition(definition.id(), definition.revision(), definition.name(), definition.source(), definition.target(), definition.nodes().stream().map(this::pin).toList(), definition.edges());
    }
    public void require(WorkflowDefinition definition, boolean pinned) {
        for (var node : definition.nodes()) {
            var d = descriptor(node.type(), node.version());
            if (node.operatorDigest() == null) {
                if (pinned) throw new WorkflowFailure(WorkflowFailure.Code.OPERATOR_PIN_REQUIRED);
            } else if (!node.operatorDigest().equals(d.digest())) throw new WorkflowFailure(WorkflowFailure.Code.OPERATOR_CHANGED);
        }
    }
    public static void configuration(WorkflowDefinition.Type type, String version, Map<String, String> config) {
        var d = builtIn().descriptor(type, version);
        if (config.size() > 32 || config.values().stream().anyMatch(v -> v.length() > 512)) invalid();
        if (d.configKind() == ConfigKind.FIELD_MAPPING) {
            if (config.isEmpty() || new HashSet<>(config.values()).size() != config.size()) invalid();
            config.forEach((from, to) -> { WorkflowDefinition.field(from); WorkflowDefinition.field(to); });
        } else {
            if (!config.keySet().equals(d.parameters().stream().map(Parameter::name).collect(java.util.stream.Collectors.toSet()))) invalid();
            for (var p : d.parameters()) switch (p.kind()) {
                case FIELD -> WorkflowDefinition.field(config.get(p.name()));
                case TEXT -> { }
                case FACTOR -> { var factor = WorkflowDefinition.decimal(config.get(p.name())); if (factor.abs().compareTo(new BigDecimal("1000000")) > 0 || factor.signum() == 0) invalid(); }
            }
        }
    }
    public WorkflowExecutionPlan compile(WorkflowDefinition definition, ModelDefinition model) {
        return compile(definition,model,null);
    }
    public WorkflowExecutionPlan compile(WorkflowDefinition definition, ModelDefinition model,MappingDefinition mapping) {
        require(definition, false);
        definition.requireOutput(model);
        if(definition.target().mappingPin()!=null&&(mapping==null||!mapping.metricKey().equals(definition.target().metricKey())))throw new WorkflowFailure(WorkflowFailure.Code.MAPPING_CHANGED);
        var metric=definition.target().mappingPin()==null?null:new WorkflowMetricPlan(definition.target().mappingPin(),mapping);
        if(metric==null&&mapping!=null)invalid();
        var parents = new LinkedHashMap<String, List<String>>();
        var resolved = new LinkedHashMap<String, Descriptor>();
        for (var node : definition.nodes()) {
            var d = descriptor(node.type(), node.version());
            var in = definition.edges().stream().filter(e -> e.to().equals(node.id())).map(WorkflowDefinition.Edge::from).toList();
            long out = definition.edges().stream().filter(e -> e.from().equals(node.id())).count();
            connections(d.input(), in.size()); connections(d.output(), out);
            parents.put(node.id(), in); resolved.put(node.id(), d);
        }
        for (var edge : definition.edges()) {
            var from = resolved.get(edge.from()).output(); var to = resolved.get(edge.to()).input();
            if (from == null || to == null || from.kind() != to.kind()) invalid();
        }
        return new WorkflowExecutionPlan(definition, model, parents, resolved,metric);
    }
    private static void connections(Port port, long count) { if (port == null ? count != 0 : count < port.minimum() || count > port.maximum()) invalid(); }
    static String semanticDigest(Descriptor d) {
        var parts = new ArrayList<String>(List.of("opsweave-operator-v1", d.type().name(), d.version(), d.implementation(), d.configKind().name(), Integer.toString(d.parameters().size())));
        for (var p : d.parameters()) parts.addAll(List.of(p.name(), p.kind().name()));
        for (String side : List.of("input", "output")) {
            parts.add(side); var port = side.equals("input") ? d.input() : d.output();
            parts.addAll(port == null ? List.of("none") : List.of(port.id(), port.kind().name(), Integer.toString(port.minimum()), Integer.toString(port.maximum())));
        }
        return WorkflowDefinition.hash(parts);
    }
    private static void invalid() { throw new IllegalArgumentException("Invalid workflow operator configuration or ports"); }
}
