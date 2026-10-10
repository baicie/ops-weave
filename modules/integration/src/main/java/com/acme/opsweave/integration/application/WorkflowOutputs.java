package com.acme.opsweave.integration.application;

import com.acme.opsweave.catalog.domain.ModelDefinition;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowRuntime.Settings;
import java.time.Instant;
import java.util.*;

/** Routes runtime rows by output target: ENTITY rows keep the entity sink, RELATION rows write
 *  relation instances (or stay pending) through the relation sink. One target kind per workflow. */
public final class WorkflowOutputs implements WorkflowRuntimeService.Output {
    private final WorkflowRuntimeService.Output entities;
    private final WorkflowRelationOutput relations;

    public WorkflowOutputs(WorkflowRuntimeService.Output entities, WorkflowRelationOutput relations) {
        this.entities = Objects.requireNonNull(entities);
        this.relations = Objects.requireNonNull(relations);
    }

    @Override
    public void validate(Principal p, WorkflowDefinition d, Settings settings, List<Map<String, Object>> input, WorkflowEvaluation evaluation) {
        if (!d.target().relationOutput()) { entities.validate(p, d, settings, input, evaluation); return; }
        for (var row : evaluation.rows()) {
            if (!row.status().equals("ACCEPTED")) continue;
            var values = row.steps().getLast().values();
            for (var field : List.of(d.target().fromField(), d.target().toField())) {
                var value = values.get(field);
                if (value == null || !(value instanceof String || value instanceof Number || value instanceof Boolean)
                    || value.toString().isBlank() || value.toString().length() > 256) throw new IllegalArgumentException("Explicit relation endpoint identity required");
            }
        }
    }

    @Override
    public String write(Principal p, WorkflowDefinition d, Settings settings, UUID execution, Instant observedAt, Map<String, Object> input, Map<String, Object> values, String origin) {
        if (d.target().relationOutput()) throw new IllegalStateException("RELATION target writes relation instances");
        return entities.write(p, d, settings, execution, observedAt, input, values, origin);
    }

    @Override
    public WorkflowRelationOutput.Outcome writeRelation(Principal p, WorkflowDefinition d, ModelDefinition model, Settings settings, UUID execution, Instant observedAt, Map<String, Object> input, Map<String, Object> values, String origin) {
        if (!d.target().relationOutput() || model == null || model.kind() != ModelDefinition.Kind.RELATION || model.endpoints() == null) throw new IllegalArgumentException("RELATION model pin required");
        var endpoints = model.endpoints();
        return relations.writeRow(p, "workflow." + d.id(), d.target().id(), d.target().revision(),
            d.target().fromField(), d.target().toField(), endpoints.from().id(), endpoints.to().id(), values, origin);
    }
}
