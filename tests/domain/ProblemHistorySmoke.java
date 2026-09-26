import com.acme.opsweave.alerting.domain.*;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.incident.application.*;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.incident.infrastructure.InMemoryIncidentStore;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;
import static com.acme.opsweave.incident.domain.IncidentFailure.Code.*;

public class ProblemHistorySmoke {
    static int checks;
    static final TenantId TENANT=new TenantId("history-test");
    static final Instant NOW=Instant.parse("2026-09-25T00:00:00.123456789Z");
    static final EntityId ENTITY=new EntityId(UUID.randomUUID());
    static final IncidentVisibility ALL=new IncidentVisibility(true,Set.of(),true,Set.of());
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Check "+checks);}
    static void fail(IncidentFailure.Code code,Runnable run){try{run.run();throw new AssertionError("Expected "+code);}catch(IncidentFailure error){check(error.code()==code);}}
    static void invalid(Runnable run){try{run.run();throw new AssertionError("Expected invalid");}catch(IllegalArgumentException error){checks++;}}
    static ExternalProblem problem(String event,Instant at,boolean recovered){return new ExternalProblem(TENANT,"zabbix-1",event,"50","Observed "+event,4,NOW.minusSeconds(600),at,List.of("10084"),false,recovered?"201":null,recovered?NOW.minusSeconds(300):null);}
    static ProblemHistoryQuery query(long version,Instant cutoff,UUID after,int limit){return new ProblemHistoryQuery(version,NOW.minusSeconds(60).getEpochSecond(),NOW.plusSeconds(60).getEpochSecond(),cutoff,"","",after,limit);}
    static Principal principal(ResourceScope scope,Set<Permission> permissions){return new Principal(new SubjectId("reader"),TENANT,permissions,scope);}
    public static void main(String[] args){
        var mappings=new HashMap<String,EntityId>();var store=new InMemoryIncidentStore((t,s,h)->Map.copyOf(mappings));
        var first=problem("101",NOW,false);var id=IncidentRecord.initialId(first);var received=NOW.plusSeconds(61);
        store.ingest(TENANT,"zabbix-1","labeled-fixture",List.of(first),received);
        var initial=store.observations(TENANT,id,ALL,query(1,received,null,25));check(initial.size()==1);check(initial.getFirst().hasUnmappedHosts());
        mappings.put("10084",ENTITY);store.ingest(TENANT,"zabbix-1","labeled-fixture",List.of(first),received.plusSeconds(1));
        check(store.find(TENANT,id,ALL).orElseThrow().entityIds().equals(Set.of(ENTITY)));
        check(store.observations(TENANT,id,ALL,query(2,received.plusSeconds(1),null,25)).equals(initial));
        var restricted=new IncidentVisibility(true,Set.of(),false,Set.of(ENTITY));
        check(store.observations(TENANT,id,restricted,query(2,received.plusSeconds(1),null,25)).isEmpty());
        check(store.observations(TENANT,id,ALL,query(2,received.minusNanos(1),null,25)).isEmpty());
        var recovered=problem("101",NOW.plusSeconds(10),true);store.ingest(TENANT,"zabbix-1","labeled-fixture",List.of(recovered),received.plusSeconds(2));
        var omitted=problem("101",NOW.plusSeconds(20),false);store.ingest(TENANT,"zabbix-1","labeled-fixture",List.of(omitted),received.plusSeconds(3));
        check(store.find(TENANT,id,ALL).orElseThrow().problems().getFirst().observation().state()==ExternalProblem.State.RECOVERED);
        var late=problem("101",NOW.minusNanos(1),false);store.ingest(TENANT,"zabbix-1","labeled-fixture",List.of(late),received.plusSeconds(4));
        var all=store.observations(TENANT,id,ALL,query(3,received.plusSeconds(5),null,25));check(all.size()==4);
        check(all.stream().anyMatch(e->e.observation().equals(omitted)&&e.observation().state()==ExternalProblem.State.ACTIVE));
        check(all.stream().anyMatch(e->e.observation().observedAt().equals(NOW.minusNanos(1))));
        fail(CONFLICT,()->store.ingest(TENANT,"zabbix-1","labeled-fixture",List.of(problem("102",NOW,false),problem("101",NOW,true)),received.plusSeconds(5)));
        check(store.find(TENANT,IncidentRecord.initialId(problem("102",NOW,false)),ALL).isEmpty());
        check(store.observations(TENANT,id,ALL,query(3,received.plusSeconds(5),null,25)).equals(all));
        var clock=Clock.fixed(received.plusSeconds(100),ZoneOffset.UTC);var incidents=new IncidentService(new AuthorizeUseCase(),store,clock);
        var service=new ProblemHistoryService(incidents,store,clock);var user=principal(ResourceScope.tenantWide(),Set.of(Permission.INCIDENT_READ,Permission.ENTITY_READ));
        var page=service.read(user,id,query(3,received.plusSeconds(5),null,1));check(page.items().size()==1);check(page.nextCursor().equals(all.getFirst().id()));
        check(service.read(user,id,query(3,received.plusSeconds(5),page.nextCursor(),25)).items().equals(all.subList(1,4)));
        fail(CONFLICT,()->service.read(user,id,query(2,received.plusSeconds(5),null,25)));
        fail(INVALID_REQUEST,()->service.read(user,id,query(3,clock.instant().plusNanos(1),null,25)));
        fail(FORBIDDEN,()->service.read(principal(ResourceScope.tenantWide(),Set.of(Permission.INCIDENT_READ)),id,query(3,clock.instant(),null,25)));
        fail(NOT_FOUND,()->store.observations(new TenantId("foreign"),id,ALL,query(3,clock.instant(),null,25)));
        var malicious=new ProblemHistoryService(incidents,(t,i,v,q)->List.of(all.getLast(),all.getFirst()),clock);
        fail(UNAVAILABLE,()->malicious.read(user,id,query(3,clock.instant(),null,25)));
        var other=problem("103",NOW,false);store.ingest(TENANT,"zabbix-1","labeled-fixture",List.of(other),received);var target=IncidentRecord.initialId(other);
        store.reorganize(TENANT,new IncidentReorganization.Request(UUID.randomUUID(),IncidentReorganization.Kind.MERGE,id,3,target,1,List.of(),null,"History ownership test"),ALL,"operator",clock.instant());
        check(store.observations(TENANT,id,ALL,query(4,clock.instant(),null,25)).isEmpty());
        check(store.observations(TENANT,target,ALL,query(2,clock.instant(),null,25)).size()==5);
        check(store.observations(TENANT,target,restricted,query(2,clock.instant(),null,25)).size()==4);
        fail(CONFLICT,()->store.observations(TENANT,target,ALL,query(1,clock.instant(),null,25)));
        invalid(()->new ProblemHistoryQuery(1,0,Long.MAX_VALUE,clock.instant(),"","",null,25));
        invalid(()->new ProblemHistoryQuery(1,0,32*86400,clock.instant(),"","",null,25));
        invalid(()->new ProblemHistoryQuery(1,0,1,clock.instant(),"","101",null,25));
        invalid(()->query(0,clock.instant(),null,25));invalid(()->query(1,clock.instant(),null,26));
        System.out.println("Problem history smoke: "+checks+" checks passed");
    }
}
