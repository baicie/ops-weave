package com.acme.opsweave.catalog.application;
import com.acme.opsweave.catalog.api.ModelCatalogStore;
import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.identity.domain.*;
import java.time.*;
import java.util.*;

/** Current authorized metadata only. Reference counts never imply a global tenant inventory. */
public final class ModelReferenceService {
    public interface Workflows { ModelReferences.Page references(Principal p,ModelRevisionReview.Pin model); }
    private final ModelCatalogStore store;private final ModelCatalogService models;private final List<ModelDefinition> builtins;private final Workflows workflows;private final Clock clock;
    public ModelReferenceService(ModelCatalogStore store,List<ModelDefinition> builtins,Workflows workflows,Clock clock){this.store=store;this.builtins=List.copyOf(builtins);this.models=new ModelCatalogService(store,builtins,clock);this.workflows=workflows;this.clock=clock;}
    public ModelReferences.Report references(Principal p,ModelDefinition.Ref ref){
        var definition=models.resolve(p,ref);var pin=ModelRevisionReview.pin(definition);var found=new ArrayList<ModelReferences.Usage>();
        var relations=store.relations(p.tenantId(),ref,51);var merged=new ArrayList<ModelDefinition>(relations.stream().map(ModelCatalogStore.Entry::definition).toList());merged.addAll(builtins.stream().filter(d->d.endpoints()!=null&&(d.endpoints().from().equals(ref)||d.endpoints().to().equals(ref))).toList());
        for(var r:merged){var roles=new ArrayList<String>();if(r.endpoints().from().equals(ref))roles.add("FROM");if(r.endpoints().to().equals(ref))roles.add("TO");found.add(new ModelReferences.Usage("RELATION",r.id(),r.revision(),r.digest(),r.label(),"PUBLISHED",0,roles,List.of(),List.of()));}
        boolean available=new Authorizer().decide(p,new ResourceRef(p.tenantId(),"workflow","*"),Permission.SOURCE_SYNC).allowed();var flows=available?workflows.references(p,pin):new ModelReferences.Page(List.of(),false);found.addAll(flows.items());
        found.sort(Comparator.comparing(ModelReferences.Usage::kind).thenComparing(ModelReferences.Usage::id).thenComparingInt(ModelReferences.Usage::revision).thenComparing(ModelReferences.Usage::state));
        return new ModelReferences.Report(pin,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),available,new ModelReferences.Page(found.stream().limit(50).toList(),found.size()>50||relations.size()>50||flows.truncated()));
    }
}
