import com.acme.opsweave.catalog.domain.*;
import com.acme.opsweave.catalog.domain.ModelDefinition.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.domain.WorkflowDefinition.*;
import com.acme.opsweave.integration.api.WorkflowStore.*;
import com.acme.opsweave.integration.application.WorkflowService;
import com.acme.opsweave.integration.infrastructure.InMemoryWorkflowStore;
import com.acme.opsweave.sharedkernel.TenantId;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class WorkflowPaginationSmoke {
 static int checks;
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Pagination check "+checks);}
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static void rejects(Runnable r){checks++;try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected rejection "+checks);}
 static void failure(WorkflowFailure.Code code,Runnable r){checks++;try{r.run();}catch(WorkflowFailure e){if(e.code()==code)return;throw new AssertionError(e.code());}throw new AssertionError("Expected "+code);}
 static void failure(WorkflowFailure.Code code,Runnable r,String message){checks++;try{r.run();}catch(WorkflowFailure e){if(e.code()==code)return;throw new AssertionError(message+" but got "+e.code());}throw new AssertionError(message);}
 static final ModelDefinition MODEL=new ModelDefinition("custom.flow",1,Kind.ENTITY,"Flow","fixture",List.of(new Field("name","Name",ModelDefinition.Type.TEXT,true,255,null,null,List.of()),new Field("count","Count",ModelDefinition.Type.INTEGER,false,null,BigDecimal.ZERO,new BigDecimal("9999"),List.of())),null);
 static Node node(String id,WorkflowDefinition.Type type,Map<String,String> c){return WorkflowOperators.builtIn().pin(new Node(id,type,"1",c));}
 static WorkflowDefinition flow(String id,int rev){var nodes=List.of(node("source",WorkflowDefinition.Type.SOURCE,Map.of()),node("map",WorkflowDefinition.Type.MAP,Map.of("raw_name","name","raw_count","count")),node("validate",WorkflowDefinition.Type.VALIDATE,Map.of()),node("output",WorkflowDefinition.Type.OUTPUT,Map.of()));var edges=List.of(new Edge("source","map"),new Edge("map","validate"),new Edge("validate","output"));return new WorkflowDefinition(id,rev,"Fixture workflow",new Source("MANUAL_SAMPLE","manual"),new Target(MODEL.id(),1,MODEL.digest()),nodes,edges);}
 static Map<String,Position> layout(WorkflowDefinition d){var result=new LinkedHashMap<String,Position>();for(int i=0;i<d.nodes().size();i++)result.put(d.nodes().get(i).id(),new Position(100,100*i));return result;}
 static Principal principal(String tenant,String owner){return new Principal(new SubjectId(owner),new TenantId(tenant),Set.of(Permission.SOURCE_SYNC,Permission.ENTITY_READ),ResourceScope.tenantWide());}
 static final class TestClock extends Clock {Instant instant=Instant.parse("2026-10-13T08:00:00Z");public Instant instant(){return instant;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}}
 static <T> List<T> pages(WorkflowService service,Principal p,int limit,Function0<WorkflowPages.CursorPage<T>> next,java.util.function.Function<T,String> key){
  var all=new ArrayList<T>();String cursor=null;int guard=0;
  while(true){checks++;if(guard++>50)throw new AssertionError("pagination does not terminate");var page=next.apply(cursor);check(page.hasMore()==(page.nextCursor()!=null));if(page.hasMore())check(!page.items().isEmpty());check(page.items().size()<=limit);
   for(var item:page.items()){check(all.stream().noneMatch(old->key.apply(old).equals(key.apply(item))));all.add(item);}
   if(!page.hasMore()){check(page.nextCursor()==null);return all;}
   cursor=page.nextCursor();}
 }
 interface Function0<T> {T apply(String cursor);}
 public static void main(String[] args){
  var store=new InMemoryWorkflowStore();var clock=new TestClock();
  var service=new WorkflowService(store,(who,t)->MODEL,(who,s,b)->{throw new AssertionError("Manual must not read source");},clock);
  var p=principal("flow-a","one");var other=principal("flow-a","two");
  // Nine drafts: eight with increasing update times plus two sharing one instant to pin the tie-break.
  for(int i=1;i<=7;i++){clock.instant=clock.instant.plusSeconds(60);var d=flow("flow-"+i,1);service.save(p,d,layout(d),0);}
  clock.instant=clock.instant.plusSeconds(60);service.save(p,flow("flow-9",1),layout(flow("flow-9",1)),0);service.save(p,flow("flow-8",1),layout(flow("flow-8",1)),0);
  var single=service.entries(p,null,null,null,50);check(!single.hasMore()&&single.nextCursor()==null&&single.items().size()==9);
  var expected=single.items().stream().map(e->e.definition().id()).toList();
  check(expected.equals(List.of("flow-8","flow-9","flow-7","flow-6","flow-5","flow-4","flow-3","flow-2","flow-1")),"update time descending with id ascending tie-break");
  var walked=pages(service,p,3,c->service.entries(p,null,null,c,3),e->e.definition().id()+"@"+e.definition().revision());
  check(walked.stream().map(e->e.definition().id()).toList().equals(expected),"cursor pages cover every retained entry exactly once");
  check(service.entries(p,"DRAFT",null,null,50).items().size()==9);
  check(service.entries(p,"PUBLISHED",null,null,50).items().isEmpty());
  check(service.entries(p,null,"flow-4",null,10).items().size()==1);
  check(service.entries(p,null,null,expected.get(2)+":1",1).items().size()==1,"continuation is strictly after the named entry");
  rejects(()->service.entries(p,null,null,null,0));rejects(()->service.entries(p,null,null,null,51));rejects(()->service.entries(p,null,null,null,-1));
  rejects(()->service.entries(p,"RUNNING",null,null,20));rejects(()->service.entries(p,null,"Bad Id",null,20));rejects(()->service.entries(p,null,null,"flow",20));
  rejects(()->service.entries(p,null,null,"flow:0",20));rejects(()->service.entries(p,null,null,"flow:1:2",20));rejects(()->service.entries(p,null,null,"FLOW:1",20));
  failure(WorkflowFailure.Code.NOT_FOUND,()->service.entries(p,null,null,"absent:1",20));
  check(service.entries(other,null,null,null,50).items().isEmpty(),"private drafts of another owner are not in the page window");
  failure(WorkflowFailure.Code.NOT_FOUND,()->service.entries(other,null,null,"flow-2:1",20));
  // Publish one draft: the draft disappears from its page window, its cursor fails closed and the
  // immutable published entry keeps the same identity in the merged page.
  var first=service.entries(p,null,"flow-1",null,10).items().getFirst();
  var preview=service.evaluate(p,"flow-1",1,1,first.digest(),false,List.of(Map.of("raw_name","fixture")),null);
  var published=service.publish(p,"flow-1",1,1,first.digest(),preview.receipt().id());
  check(published.state().equals("PUBLISHED")&&published.editVersion()==0);
  check(service.entries(p,"DRAFT",null,null,50).items().size()==8);
  var publishedPage=service.entries(p,"PUBLISHED",null,null,50);check(publishedPage.items().size()==1&&publishedPage.items().getFirst().definition().id().equals("flow-1"));
  failure(WorkflowFailure.Code.NOT_FOUND,()->service.entries(p,"DRAFT",null,"flow-1:1",20),"a superseded draft cursor is explicit, never guessed");
  check(service.entries(p,null,null,"flow-1:1",20).items().size()==8,"the merged cursor still locates the published entry");
  check(service.entries(other,null,null,"flow-1:1",20).items().isEmpty(),"published continuation is readable for the tenant");
  // Runs page with the same closed semantics: the flow-1 preview plus five explicit previews.
  for(int i=2;i<=6;i++){var entry=service.entries(p,null,"flow-"+i,null,10).items().getFirst();service.evaluate(p,"flow-"+i,1,1,entry.digest(),false,List.of(Map.of("raw_name","r"+i)),null);}
  check(service.runs(p).items().size()==6);
  var runPages=pages(service,p,2,c->service.runs(p,c,2),r->r.receipt().id().toString());
  check(runPages.size()==6,"cursor pages cover every retained run exactly once");
  check(service.runs(p,null,2).nextCursor().equals(service.runs(p,null,2).items().getLast().receipt().id().toString()),"the run cursor is the last returned receipt");
  var tail=service.runs(p,runPages.getLast().receipt().id().toString(),2);
  check(tail.items().isEmpty()&&!tail.hasMore()&&tail.nextCursor()==null,"an empty tail page is a complete answer");
  failure(WorkflowFailure.Code.NOT_FOUND,()->service.runs(p,UUID.randomUUID().toString(),2));
  failure(WorkflowFailure.Code.NOT_FOUND,()->service.runs(other,runPages.getFirst().receipt().id().toString(),2),"another owner's run cursor resolves to nothing");
  rejects(()->service.runs(p,"not-a-uuid",2));rejects(()->service.runs(p,"10000000-0000-4000-8000-00000000007",2));rejects(()->service.runs(p,null,0));rejects(()->service.runs(p,null,51));
  System.out.println("WorkflowPaginationSmoke: "+checks+" checks passed");
 }
}
