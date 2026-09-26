import com.acme.opsweave.alerting.domain.ExternalProblem;
import com.acme.opsweave.incident.domain.*;
import com.acme.opsweave.incident.domain.IncidentReorganization.*;
import com.acme.opsweave.incident.infrastructure.InMemoryIncidentStore;
import com.acme.opsweave.incident.application.IncidentService;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

public class IncidentReorganizationSmoke {
    static int checks; static final TenantId TENANT=new TenantId("reorganization-smoke"); static final Instant NOW=Instant.parse("2026-09-25T00:00:00Z");
    static final IncidentVisibility ALL=new IncidentVisibility(true,Set.of(),true,Set.of());
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Check "+checks);}
    static void fail(Runnable action){try{action.run();throw new AssertionError("Expected failure");}catch(IncidentFailure|IllegalArgumentException e){checks++;}}
    static ExternalProblem problem(String event,boolean recovered,Instant now){return new ExternalProblem(TENANT,"zabbix-1",event,"10","Fixture problem "+event,3,NOW.minusSeconds(60),now,List.of(),false,recovered?"9001":null,recovered?NOW.minusSeconds(20):null);}
    static Request merge(UUID a,long av,UUID b,long bv){return new Request(UUID.randomUUID(),Kind.MERGE,a,av,b,bv,List.of(),null,"Operator reviewed shared incident");}
    public static void main(String[] args){
        var store=new InMemoryIncidentStore((t,s,h)->Map.of());var a=problem("1001",false,NOW);var b=problem("1002",false,NOW);
        UUID aid=IncidentRecord.initialId(a),bid=IncidentRecord.initialId(b);store.ingest(TENANT,"zabbix-1","labeled-fixture",List.of(a,b),NOW);
        var request=merge(aid,1,bid,1);var result=store.reorganize(TENANT,request,ALL,"operator",NOW.plusSeconds(1));
        check(result.sourceVersion()==2&&result.targetVersion()==2);check(store.find(TENANT,aid,ALL).orElseThrow().merged());
        check(store.page(TENANT,ALL,null,null,25).size()==1);check(store.find(TENANT,bid,ALL).orElseThrow().problems().size()==2);
        check(store.reorganize(TENANT,request,ALL,"operator",NOW.plusSeconds(2)).equals(result));
        fail(()->store.reorganize(TENANT,request,ALL,"other",NOW.plusSeconds(2)));
        fail(()->store.reorganize(TENANT,merge(aid,2,bid,2),ALL,"operator",NOW.plusSeconds(2)));
        var imported=store.ingest(TENANT,"zabbix-1","labeled-fixture",List.of(problem("1001",true,NOW.plusSeconds(3)),problem("1002",true,NOW.plusSeconds(3))),NOW.plusSeconds(3));
        check(imported.createdIncidents()==0&&imported.changedIncidents()==1);
        var target=store.find(TENANT,bid,ALL).orElseThrow();check(target.problems().stream().allMatch(p->p.observation().state()==ExternalProblem.State.RECOVERED));
        check(target.timeline().size()==4);check(store.find(TENANT,aid,ALL).orElseThrow().problems().getFirst().observation().state()==ExternalProblem.State.ACTIVE);
        check(target.organization().version()==2&&target.incident().version()==4);
        var split=new Request(UUID.randomUUID(),Kind.SPLIT,bid,4,UUID.randomUUID(),0,List.of(new ProblemKey("zabbix-1","1001")),"Separated investigation","Operator reviewed independent scope");
        var divided=store.reorganize(TENANT,split,ALL,"operator",NOW.plusSeconds(4));
        fail(()->new Receipt(split,"operator",5,1,List.of(new ProblemKey("zabbix-1","1002")),NOW.plusSeconds(4)));
        check(divided.targetVersion()==1&&divided.sourceVersion()==5);check(store.page(TENANT,ALL,null,null,25).size()==2);
        var child=store.find(TENANT,split.targetIncidentId(),ALL).orElseThrow();check(child.incident().status()==IncidentStatus.OPEN&&child.timeline().size()==2);
        check(child.problems().getFirst().firstReceivedAt().equals(NOW));check(store.find(TENANT,bid,ALL).orElseThrow().problems().getFirst().observation().problemEventId().equals("1002"));
        check(store.reorganize(TENANT,split,ALL,"operator",NOW.plusSeconds(5)).equals(divided));
        check(store.reorganization(TENANT,split.requestKey(),ALL).orElseThrow().equals(divided));
        check(store.reorganization(new TenantId("other"),split.requestKey(),ALL).isEmpty());
        var oneOnly=new IncidentVisibility(false,Set.of(bid),true,Set.of());check(store.reorganization(TENANT,split.requestKey(),oneOnly).isEmpty());
        store.ingest(TENANT,"zabbix-1","labeled-fixture",List.of(problem("1001",true,NOW.plusSeconds(8))),NOW.plusSeconds(8));
        check(store.find(TENANT,split.targetIncidentId(),ALL).orElseThrow().problems().getFirst().lastReceivedAt().equals(NOW.plusSeconds(8)));
        fail(()->store.reorganize(TENANT,new Request(UUID.randomUUID(),Kind.SPLIT,bid,5,UUID.randomUUID(),0,List.of(new ProblemKey("zabbix-1","1002")),"Empty parent","Invalid split all"),ALL,"operator",NOW.plusSeconds(10)));
        fail(()->store.reorganize(TENANT,merge(bid,4,split.targetIncidentId(),1),ALL,"operator",NOW.plusSeconds(10)));
        check(store.find(TENANT,bid,ALL).orElseThrow().incident().version()==5);
        var service=new IncidentService(new AuthorizeUseCase(),store,Clock.fixed(NOW.plusSeconds(10),ZoneOffset.UTC));
        var reader=new Principal(new SubjectId("reader"),TENANT,Set.of(Permission.INCIDENT_READ,Permission.ENTITY_READ),ResourceScope.tenantWide());
        fail(()->service.reorganize(reader,merge(bid,5,split.targetIncidentId(),1)));
        check(service.reorganization(reader,split.requestKey()).equals(divided));
        fail(()->IncidentProjection.transition(store.find(TENANT,aid,ALL).orElseThrow(),2,IncidentStatus.INVESTIGATING,UUID.randomUUID(),"operator",NOW.plusSeconds(10)));
        System.out.println("Incident reorganization smoke: "+checks+" checks passed");
    }
}
