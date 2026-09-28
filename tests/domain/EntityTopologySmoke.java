import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.inventory.application.ReadEntityTopology;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;
public final class EntityTopologySmoke {
 static int checks;static void check(boolean value){checks++;if(!value)throw new AssertionError("Topology "+checks);}static void rejects(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError("Expected rejection");}
 public static void main(String[] args){var tenant=new TenantId("fixture-topology");var a=new EntityId(UUID.randomUUID());var b=new EntityId(UUID.randomUUID());var at=Instant.parse("2026-09-28T00:00:00Z");var na=new EntityTopology.Node(a,"Fixture A","Host","ACTIVE","fixture");var nb=new EntityTopology.Node(b,"Fixture B","Host","ACTIVE","fixture");var edge=new EntityTopology.Edge(UUID.randomUUID(),a,b,"depends_on",at.minusSeconds(1),null,"fixture");var full=new EntityTopology(tenant,a,at,List.of(na,nb),List.of(edge),false);check(full.edges().size()==1);
  rejects(()->new EntityTopology(tenant,a,at,List.of(na,nb),List.of(),false));rejects(()->new EntityTopology(tenant,a,at,List.of(na),List.of(edge),false));rejects(()->new EntityTopology(tenant,a,at,List.of(na,na),List.of(),false));rejects(()->new EntityTopology(tenant,a,at,List.of(na),List.of(),true));rejects(()->new EntityTopology.Node(a,"Fixture","Host","ACTIVE","real"));rejects(()->new EntityTopology(tenant,a,at,List.of(na,nb),List.of(new EntityTopology.Edge(UUID.randomUUID(),a,b,"depends_on",at,at,"fixture")),false));
  var p=new Principal(new SubjectId("reader"),tenant,Set.of(Permission.ENTITY_READ),ResourceScope.tenantWide());var clock=Clock.fixed(at,ZoneOffset.UTC);var auth=new AuthorizeUseCase();var reader=new ReadEntityTopology(auth,(t,id,s,time)->{check(t.equals(tenant)&&id.equals(a)&&s.all()&&time.equals(at));return Optional.of(full);},clock);check(reader.read(p,a).isPresent());
  var scoped=new Principal(p.subjectId(),tenant,p.permissions(),ResourceScope.of(Set.of(ResourceRef.entity(tenant,a))));var never=new ReadEntityTopology(auth,(t,id,s,time)->{throw new AssertionError("Unauthorized storage access");},clock);check(never.read(scoped,b).isEmpty());check(never.read(new Principal(p.subjectId(),tenant,Set.of(),p.resourceScope()),a).isEmpty());rejects(()->new ReadEntityTopology(auth,(t,id,s,time)->Optional.of(full),clock).read(scoped,a));rejects(()->new ReadEntityTopology(auth,(t,id,s,time)->Optional.of(full),Clock.offset(clock,Duration.ofSeconds(1))).read(p,a));
  rejects(()->new EntityTopology(tenant,a,at,List.of(na,nb),List.of(new EntityTopology.Edge(UUID.randomUUID(),a,b,"depends_on",at.plusSeconds(1),null,"fixture")),false));rejects(()->new EntityTopology(tenant,a,at,List.of(na,nb),List.of(new EntityTopology.Edge(UUID.randomUUID(),a,b,"depends_on",at.minusSeconds(10),at,"fixture")),false));System.out.println("EntityTopologySmoke: "+checks+" checks passed");
 }
}
