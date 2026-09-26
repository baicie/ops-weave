import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.inventory.api.SourceReviewStore;
import com.acme.opsweave.inventory.application.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

public class SourceReviewSmoke {
    static int checks;
    static final Instant NOW = Instant.parse("2026-09-25T00:00:10.987654321Z");
    static final TenantId TENANT = new TenantId("review-smoke");
    static final String SOURCE = "cmdb-import-dev";
    static final ExternalObjectKey KEY = new ExternalObjectKey(TENANT,"zabbix-1","host","10084","1");
    static final EntityId ID = EntityIds.fromExternal(KEY);
    static void ok(boolean value) { if (!value) throw new AssertionError("check " + (checks+1)); checks++; }
    static void fails(Class<? extends Throwable> type, Runnable action) { try { action.run(); throw new AssertionError("Expected " + type); } catch (Throwable failed) { if (!type.isInstance(failed)) throw new AssertionError(failed); checks++; } }
    static void ingest(InMemoryInventoryStore store, EntityId id, ExternalObjectKey key, String name, String ref, Instant at) {
        var attrs = Map.<String,Object>of("ip","10.0.0.1","owner", name + " owner", "status","enabled");
        store.upsert(new Entity(id,TENANT,"host",name,Lifecycle.ACTIVE,1,at,attrs),new Observation(ref,key,id,at,at,attrs,"raw:"+ref,1),new ExternalLink(id,key));
    }
    static SourceReviewStore.Import input(UUID id, long version, String external) { return new SourceReviewStore.Import(id,version,new ExternalObjectKey(TENANT,SOURCE,"cmdb-host",external,"1"),NOW,Map.of("name","CMDB name","owner","CMDB owner"),CmdbImportPipeline.DIGEST,"operator"); }
    static SourceReview.Command command(SourceReview.Action action, long e, int r) { return new SourceReview.Command(UUID.randomUUID(),action,e,r, action == SourceReview.Action.ACCEPT ? Map.of("name",SourceReview.Choice.SUPPLEMENTAL,"owner",SourceReview.Choice.PRIMARY) : Map.of(),"manually checked","operator"); }
    public static void main(String[] args) {
        var store = new InMemoryInventoryStore(); ingest(store,ID,KEY,"first","a",NOW);
        var input = input(UUID.randomUUID(),1,"cmdb-1"); var staged = store.stage(TENANT,ID,input,NOW);
        ok(store.find(TENANT,ID).orElseThrow().name().equals("first")); ok(staged.status() == SourceReview.Status.PENDING);
        ok(store.stage(TENANT,ID,input,NOW.plusSeconds(5)).equals(staged));
        fails(UnsupportedOperationException.class, () -> staged.values().put("name","changed"));
        fails(SourceReview.Conflict.class, () -> store.stage(TENANT,ID,input(input.id(),1,"changed"),NOW));
        var accept = command(SourceReview.Action.ACCEPT,1,1); var accepted = store.decide(TENANT,ID,SOURCE,staged.id(),accept,NOW);
        ok(accepted.status() == SourceReview.Status.ACCEPTED); ok(store.find(TENANT,ID).orElseThrow().name().equals("CMDB name"));
        ok(store.find(TENANT,ID).orElseThrow().attributes().get("owner").equals("first owner"));
        ok(store.find(TENANT,ID).orElseThrow().version() == 2); ok(store.observations().size() == 2);
        ok(store.decide(TENANT,ID,SOURCE,staged.id(),accept,NOW).equals(accepted)); ok(store.find(TENANT,ID).orElseThrow().version() == 2);
        fails(SourceReview.Conflict.class, () -> store.decide(TENANT,ID,SOURCE,staged.id(),new SourceReview.Command(accept.requestId(),accept.action(),1,1,accept.choices(),"different","operator"),NOW));
        ingest(store,ID,KEY,"latest","b",NOW.plusNanos(1));
        ok(store.find(TENANT,ID).orElseThrow().name().equals("CMDB name")); ok(store.find(TENANT,ID).orElseThrow().attributes().get("owner").equals("latest owner"));
        ingest(store,ID,KEY,"late older","c",NOW.minusSeconds(1)); ok(store.find(TENANT,ID).orElseThrow().version() == 3);
        ok(store.observation(TENANT,"b").orElseThrow().fields().get("owner").equals("latest owner"));
        ok(store.linkOf(KEY).orElseThrow().entityId().equals(ID));
        var newer = store.stage(TENANT,ID,input(UUID.randomUUID(),3,"cmdb-2"),NOW.plusSeconds(2));
        fails(SourceReview.Conflict.class, () -> store.decide(TENANT,ID,SOURCE,newer.id(),command(SourceReview.Action.ACCEPT,3,1),NOW.plusSeconds(2)));
        var revoked = store.decide(TENANT,ID,SOURCE,staged.id(),command(SourceReview.Action.REVOKE,3,2),NOW.plusSeconds(3));
        ok(revoked.status() == SourceReview.Status.REVOKED); ok(store.find(TENANT,ID).orElseThrow().name().equals("latest"));
        ok(!store.find(TENANT,ID).orElseThrow().attributes().containsKey("fieldAuthority"));
        ok(store.decide(TENANT,ID,SOURCE,staged.id(),accept,NOW.plusSeconds(4)).equals(accepted));
        fails(SourceReview.Conflict.class, () -> store.decide(TENANT,ID,SOURCE,newer.id(),command(SourceReview.Action.ACCEPT,4,1),NOW.plusSeconds(5)));
        var rejected = store.decide(TENANT,ID,SOURCE,newer.id(),command(SourceReview.Action.REJECT,4,1),NOW.plusSeconds(5)); ok(rejected.status() == SourceReview.Status.REJECTED);
        ok(store.find(TENANT,ID).orElseThrow().version() == 4);
        var expired = store.stage(TENANT,ID,input(UUID.randomUUID(),4,"old"),NOW);
        fails(SourceReview.Conflict.class, () -> store.decide(TENANT,ID,SOURCE,expired.id(),command(SourceReview.Action.ACCEPT,4,1),NOW.plus(SourceReview.MAX_AGE)));
        ok(expired.stale(NOW.plus(SourceReview.MAX_AGE))); ok(!expired.stale(NOW.plus(SourceReview.MAX_AGE).minusNanos(1)));
        fails(IllegalArgumentException.class, () -> store.stage(TENANT,ID,input(UUID.randomUUID(),4,"old"),NOW.minusNanos(1)));
        fails(IllegalArgumentException.class, () -> CmdbImportPipeline.map(Map.of("tenantId","injected")));
        fails(IllegalArgumentException.class, () -> CmdbImportPipeline.map(Map.of("owner","x\ny")));
        fails(IllegalArgumentException.class, () -> CmdbImportPipeline.map(Map.of("ip","x".repeat(129))));
        ok(CmdbImportPipeline.DIGEST.equals("sha256:c8c61f0ddd773a03128464f25babc956e850a46e52baaf39568f03816fd1a4bf"));
        fails(IllegalArgumentException.class, () -> PipelineVersion.of(CmdbImportPipeline.DEFINITION));
        ok(store.reviews(new TenantId("other"),ID,SOURCE,null,25).items().isEmpty()); ok(store.reviews(TENANT,ID,"other",null,25).items().isEmpty());
        var page = store.reviews(TENANT,ID,SOURCE,null,1); ok(page.items().size() == 2); ok(page.active() == null);
        ok(store.reviews(TENANT,ID,SOURCE,page.items().getFirst().id(),1).items().getFirst().id().equals(page.items().get(1).id()));
        var auth = new AuthorizeUseCase(); var service = new SourceReviewService(auth,new GetEntityUseCase(auth,store),store,Clock.fixed(NOW,ZoneOffset.UTC),SOURCE);
        var denied = new Principal(new SubjectId("reader"),TENANT,Set.of(Permission.ENTITY_READ),ResourceScope.tenantWide());
        fails(SourceReviewService.Access.class, () -> service.page(denied,ID,null,25));
        var allowed = new Principal(new SubjectId("manager"),TENANT,Set.of(Permission.ENTITY_READ,Permission.ENTITY_MANAGE,Permission.SOURCE_SYNC),ResourceScope.tenantWide());
        ok(!service.page(allowed,ID,null,25).items().isEmpty());
        var entityOnly = new Principal(allowed.subjectId(),TENANT,allowed.permissions(),ResourceScope.of(Set.of(ResourceRef.entity(TENANT,ID))));
        fails(SourceReviewService.Access.class, () -> service.page(entityOnly,ID,null,25));
        var sourceOnly = new Principal(allowed.subjectId(),TENANT,allowed.permissions(),ResourceScope.of(Set.of(ResourceRef.source(TENANT,SOURCE))));
        fails(SourceReviewService.Access.class, () -> service.page(sourceOnly,ID,null,25));
        var missing = new EntityId(UUID.randomUUID()); fails(SourceReviewService.Access.class, () -> service.page(allowed,missing,null,25));
        var nextInput = input(UUID.randomUUID(),4,"live"); var next = store.stage(TENANT,ID,nextInput,NOW); store.decide(TENANT,ID,SOURCE,next.id(),command(SourceReview.Action.ACCEPT,4,1),NOW);
        ok(store.retireMissing(TENANT,"zabbix-1","host",Set.of()) == 1); ok(store.find(TENANT,ID).orElseThrow().lifecycle().equals("INACTIVE"));
        store.decide(TENANT,ID,SOURCE,next.id(),command(SourceReview.Action.REVOKE,6,2),NOW); ok(store.find(TENANT,ID).orElseThrow().lifecycle().equals("INACTIVE"));
        System.out.println("Source review smoke: " + checks + " checks passed");
    }
}
