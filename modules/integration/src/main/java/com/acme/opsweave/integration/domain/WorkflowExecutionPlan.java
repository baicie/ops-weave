package com.acme.opsweave.integration.domain;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import java.util.*;

/** One bounded in-memory plan per evaluation, with parents resolved before reading samples. */
public final class WorkflowExecutionPlan {
    private final WorkflowDefinition definition;
    private final ModelDefinition model;
    private final Map<String, List<String>> parents;
    private final Map<String, WorkflowOperators.Descriptor> operators;
    private final WorkflowMetricPlan metric;
    WorkflowExecutionPlan(WorkflowDefinition definition, ModelDefinition model, Map<String, List<String>> parents,
                          Map<String, WorkflowOperators.Descriptor> operators, WorkflowMetricPlan metric) {
        this.definition = definition; this.model = model;
        this.metric=metric;
        var copy = new LinkedHashMap<String, List<String>>(); parents.forEach((id, values) -> copy.put(id, List.copyOf(values)));
        this.parents = Collections.unmodifiableMap(copy); this.operators = Map.copyOf(operators);
    }
    public WorkflowDefinition definition() { return definition; }
    public ModelDefinition model() { return model; }
    public List<String> parents(String nodeId) { return parents.get(nodeId); }
    public Map<String, WorkflowOperators.Descriptor> operators() { return operators; }
    public WorkflowMetricPlan metric(){return metric;}
}
