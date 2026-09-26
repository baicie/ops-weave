import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.application.*;
import com.acme.opsweave.inventory.api.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.integration.domain.CmdbImportPipeline;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

public final class AssetIdentitySmoke {
    static int checks; static final Instant NOW=Instant.parse("2026-09-25T00:00:00.123456789Z"); static final TenantId T=new TenantId("identity-smoke");
    static final String NS="enterprise-assets", VALUE="a2e021a0-96aa-4fdd-b5cf-5a6b9113d690", SOURCE="cmdb-import";
    static void ok(boolean value){if(!value)throw new AssertionError("check "+(checks+1));checks++;}
    static void fails(Class<? extends Throwable> type,Runnable run){try{run.run();throw new AssertionError("Expected "+type);}catch(Throwable e){if(!type.isInstance(e))throw new AssertionError(e);checks++;}}
    static EntityId seed(InMemoryInventoryStore store,TenantId tenant,String external){var key=new ExternalObjectKey(tenant,"zabbix-1","host",external,"1");var id=EntityIds.fromExternal(key);var fields=Map.<String,Object>of("ip","10.0.0.1");
        store.upsert(new Entity(id,tenant,"host","Same name",Lifecycle.ACTIVE,1,NOW,fields),new Observation("seed-"+external,key,id,NOW,NOW,fields,"raw:"+external,1),new ExternalLink(id,key));return id;}
    static AssetIdentity.Command claim(long version,String value){var id=UUID.randomUUID();return new AssetIdentity.Command(id,AssetIdentity.Action.ASSERT,id,version,value,"operator","Checked asset register");}
    static AssetIdentity.Command revoke(UUID identity,long version){return new AssetIdentity.Command(UUID.randomUUID(),AssetIdentity.Action.REVOKE,identity,version,null,"operator","Incorrect registration");}
    static SourceReviewStore.Import input(AssetIdentity.Pin pin,long version){return new SourceReviewStore.Import(UUID.randomUUID(),version,new ExternalObjectKey(T,SOURCE,"cmdb-host","asset-1","1"),NOW,Map.of("owner","CMDB owner"),CmdbImportPipeline.DIGEST,"operator",pin);}
    static SourceReview.Command reviewCommand(SourceReview.Action action,long version,int reviewVersion){return new SourceReview.Command(UUID.randomUUID(),action,version,reviewVersion,action==SourceReview.Action.ACCEPT?Map.of("owner",SourceReview.Choice.SUPPLEMENTAL):Map.of(),"Checked fields","operator");}
    public static void main(String[] args) throws Exception {
        var store=new InMemoryInventoryStore();var a=seed(store,T,"1");var b=seed(store,T,"2");var cmd=claim(1,VALUE);var receipt=store.change(T,a,NS,cmd,NOW);
        ok(receipt.entityVersion()==2);ok(store.change(T,a,NS,cmd,NOW.plusSeconds(1)).equals(receipt));ok(store.find(T,a).orElseThrow().version()==2);
        ok(store.resolve(T,NS,VALUE).orElseThrow().identity().entityId().equals(a));ok(store.resolve(T,"other-namespace",VALUE).isEmpty());ok(store.resolve(new TenantId("hidden"),NS,VALUE).isEmpty());
        fails(SourceReview.Conflict.class,()->store.change(T,b,NS,claim(1,VALUE),NOW));ok(store.find(T,b).orElseThrow().version()==1);ok(store.identities(T,b,NS,null,25).isEmpty());
        fails(SourceReview.Conflict.class,()->store.change(T,a,NS,new AssetIdentity.Command(cmd.requestId(),cmd.action(),cmd.identityId(),1,VALUE,"other-actor",cmd.reason()),NOW));
        var other=new TenantId("other-tenant");var otherEntity=seed(store,other,"1");ok(store.change(other,otherEntity,NS,claim(1,VALUE),NOW).identity().tenantId().equals(other));
        var pin=receipt.identity().pin();var staged=store.stage(T,a,input(pin,2),NOW);ok(staged.identity().equals(pin));
        var revoked=store.change(T,a,NS,revoke(receipt.identity().id(),2),NOW);ok(!revoked.identity().active());ok(store.resolve(T,NS,VALUE).isEmpty());
        fails(SourceReview.Conflict.class,()->store.stage(T,a,input(pin,3),NOW));fails(SourceReview.Conflict.class,()->store.decide(T,a,SOURCE,staged.id(),reviewCommand(SourceReview.Action.ACCEPT,3,1),NOW));
        ok(store.find(T,a).orElseThrow().name().equals("Same name"));ok(store.find(T,a).orElseThrow().version()==3);
        var second=store.change(T,b,NS,claim(1,VALUE),NOW);ok(store.resolve(T,NS,VALUE).orElseThrow().identity().entityId().equals(b));
        fails(SourceReview.Conflict.class,()->store.stage(T,b,input(pin,2),NOW));var review=store.stage(T,b,input(second.identity().pin(),2),NOW);
        var accepted=store.decide(T,b,SOURCE,review.id(),reviewCommand(SourceReview.Action.ACCEPT,2,1),NOW);ok(accepted.identity().equals(second.identity().pin()));
        fails(SourceReview.Conflict.class,()->store.change(T,b,NS,revoke(second.identity().id(),3),NOW));ok(store.find(T,b).orElseThrow().version()==3);
        store.decide(T,b,SOURCE,review.id(),reviewCommand(SourceReview.Action.REVOKE,3,2),NOW);ok(!store.change(T,b,NS,revoke(second.identity().id(),4),NOW).identity().active());
        var budgetEntity=seed(store,T,"3");for(int i=0;i<16;i++)store.change(T,budgetEntity,NS,claim(i+1,UUID.randomUUID().toString()),NOW);
        fails(IllegalStateException.class,()->store.change(T,budgetEntity,NS,claim(17,UUID.randomUUID().toString()),NOW));ok(store.identities(T,budgetEntity,NS,null,25).size()==16);ok(store.find(T,budgetEntity).orElseThrow().version()==17);
        var page=store.identities(T,budgetEntity,NS,null,1);ok(page.size()==2);ok(store.identities(T,budgetEntity,NS,page.getFirst().id(),1).getFirst().id().equals(page.get(1).id()));
        for(String invalid:List.of("","Default string","10.0.0.1","00000000-0000-0000-0000-000000000000","ffffffff-ffff-ffff-ffff-ffffffffffff",VALUE.toUpperCase(),VALUE+"\n"))fails(IllegalArgumentException.class,()->AssetIdentity.value(invalid));
        for(String invalid:List.of("","../assets","Tenant","x".repeat(65),"assets\n"))fails(IllegalArgumentException.class,()->AssetIdentity.namespace(invalid));
        var authorization=new AuthorizeUseCase();var get=new GetEntityUseCase(authorization,store);var service=new AssetIdentityService(authorization,get,store,NS,SOURCE,Clock.fixed(NOW,ZoneOffset.UTC));
        var permissions=Set.of(Permission.ENTITY_READ,Permission.ENTITY_MANAGE,Permission.SOURCE_SYNC);var p=new Principal(new SubjectId("operator"),T,permissions,ResourceScope.tenantWide());
        fails(SourceReview.Conflict.class,()->service.change(p,a,UUID.randomUUID(),AssetIdentity.Action.REVOKE,receipt.identity().id(),3,null,"already revoked"));
        var requestId=UUID.randomUUID();var current=service.change(p,a,requestId,AssetIdentity.Action.ASSERT,requestId,3,VALUE,"Reverified authoritative registration");
        ok(service.resolve(p,VALUE).identity().id().equals(current.identity().id()));ok(service.page(p,a,null,25).size()==2);
        var hidden=new Principal(p.subjectId(),T,permissions,ResourceScope.of(Set.of(ResourceRef.source(T,SOURCE),ResourceRef.entity(T,b))));
        try{service.resolve(hidden,VALUE);throw new AssertionError("Hidden resolution");}catch(SourceReviewService.Access denied){ok(denied.status()==404);}
        var denied=new Principal(p.subjectId(),T,Set.of(Permission.ENTITY_READ,Permission.SOURCE_SYNC),ResourceScope.tenantWide());
        try{service.resolve(denied,VALUE);throw new AssertionError("Missing manage permission");}catch(SourceReviewService.Access e){ok(e.status()==403);}
        var disabled=new AssetIdentityService(authorization,get,store,"",SOURCE,Clock.fixed(NOW,ZoneOffset.UTC));
        try{disabled.resolve(p,VALUE);throw new AssertionError("Disabled resolver");}catch(SourceReviewService.Access e){ok(e.status()==503);}
        var race=new InMemoryInventoryStore();var ra=seed(race,T,"race-a");var rb=seed(race,T,"race-b");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var tasks=List.of(ra,rb).stream().map(id->pool.submit(()->{try{race.change(T,id,NS,claim(1,VALUE),NOW);return 1;}catch(SourceReview.Conflict expected){return 0;}})).toList();
            ok(tasks.get(0).get()+tasks.get(1).get()==1);ok(race.resolve(T,NS,VALUE).isPresent());
        }
        System.out.println("AssetIdentitySmoke: "+checks+" checks passed");
    }
}
