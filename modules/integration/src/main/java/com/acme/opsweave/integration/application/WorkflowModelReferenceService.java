package com.acme.opsweave.integration.application;
import com.acme.opsweave.catalog.application.ModelReferenceService;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.integration.api.WorkflowStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.identity.domain.*;
import java.util.*;

/** No upstream reads, original records, credentials or other subjects' tasks enter a model reference. */
public final class WorkflowModelReferenceService implements ModelReferenceService.Workflows {
    private final WorkflowStore store;private final WorkflowService workflows;
    public WorkflowModelReferenceService(WorkflowStore store,WorkflowService workflows){this.store=store;this.workflows=workflows;}
    public ModelReferences.Page references(Principal p,ModelRevisionReview.Pin model){workflows.authorize(p);return store.transaction(p.tenantId(),s->{
        var owner=p.subjectId().value();var candidates=new ArrayList<>(s.published());candidates.addAll(s.drafts(owner));var found=new ArrayList<ModelReferences.Usage>();
        for(var e:candidates){var d=e.definition();var target=d.target();if(!target.entity()||!target.id().equals(model.id())||target.revision()!=model.revision())continue;
            if(new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow",d.id()),Permission.SOURCE_SYNC).denied())continue;
            try{workflows.accessibleEntry(p,s,e);}catch(WorkflowFailure denied){if(denied.code()==WorkflowFailure.Code.FORBIDDEN)continue;throw denied;}catch(PipelineException denied){if(denied.code()==PipelineException.Code.FORBIDDEN)continue;throw denied;}
            if(!target.digest().equals(model.digest()))throw new WorkflowFailure(WorkflowFailure.Code.MODEL_CHANGED);
            var fields=new TreeSet<String>();for(var node:d.nodes()){if(node.type()==WorkflowDefinition.Type.MAP)fields.addAll(node.config().values());else if(node.config().containsKey("field"))fields.add(node.config().get("field"));}
            var tasks=new ArrayList<ModelReferences.Task>();if(e.state().equals("PUBLISHED")){
                s.task(owner,d.id()).filter(t->t.revision()==d.revision()&&t.digest().equals(e.digest())).ifPresent(t->tasks.add(new ModelReferences.Task("HOST_SCAN",t.state(),t.generation())));
                s.hostSchedule(owner,d.id()).filter(t->t.revision()==d.revision()&&t.digest().equals(e.digest())).ifPresent(t->tasks.add(new ModelReferences.Task("HOST_SCHEDULE",t.state(),t.generation())));
            }
            found.add(new ModelReferences.Usage("WORKFLOW",d.id(),d.revision(),e.digest(),d.name(),e.state(),e.editVersion(),List.of("OUTPUT"),fields.stream().limit(32).toList(),tasks));
        }
        found.sort(Comparator.comparing(ModelReferences.Usage::id).thenComparingInt(ModelReferences.Usage::revision).thenComparing(ModelReferences.Usage::state));return new ModelReferences.Page(found.stream().limit(50).toList(),found.size()>50);
    });}
}
