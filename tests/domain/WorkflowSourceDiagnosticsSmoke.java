import com.acme.opsweave.integration.application.*;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import java.time.*;
import java.util.*;
import java.net.URI;

/** Explicit source protocol fixtures. No provider or persistence connectivity claim. */
public final class WorkflowSourceDiagnosticsSmoke {
 static int checks;static void check(boolean value){checks++;if(!value)throw new AssertionError("Source diagnostics "+checks);}
 static WorkflowFailure fail(Runnable action){checks++;try{action.run();}catch(WorkflowFailure f){return f;}throw new AssertionError("Expected source failure");}
 static void invalid(Runnable action){checks++;try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid measured metadata");}
 static final Instant FROM=WorkflowDiagnosticsSmoke.FROM,TILL=FROM.plusSeconds(60),NOW=TILL.plusSeconds(10);
 static WorkflowDiagnostics.Observation latest(WorkflowMetricOutputSmoke.Fixture f){return f.store.transaction(f.p.tenantId(),s->s.diagnostics(f.p.subjectId().value(),f.d.id(),1,f.d.digest())).getFirst().observation();}
 public static void main(String[] args)throws Exception{
  var good=new WorkflowMetricStreamSmoke.Fixture();good.command(WorkflowMetricStream.Operation.START,0);good.time.advance(11);good.tick();var o=latest(good.f);check(o.sourceRead().attempts()==1&&o.sourceRead().completed()==1&&o.sourceRead().failed()==0);check(o.sourceRead().received()==2&&o.result().received()==2);check(o.dispatch()!=null&&o.queueWaitMillis()==0&&o.queueWaitMillis()==o.dispatch().waitMillis());
  var empty=new WorkflowMetricStreamSmoke.Fixture();empty.empty=true;empty.command(WorkflowMetricStream.Operation.START,0);empty.time.advance(11);empty.tick();check(latest(empty.f).sourceRead().received()==0);
  var down=new WorkflowMetricStreamSmoke.Fixture();down.unavailable=true;down.command(WorkflowMetricStream.Operation.START,0);down.time.advance(11);down.tick();o=latest(down.f);check(o.sourceRead().failed()==1&&o.sourceRead().attempts()==1&&o.sourceRead().received()==null);check(o.sourceRead().failureCode()==WorkflowDiagnostics.SourceFailure.UNAVAILABLE&&o.result().received()==null);check(down.f.sink.writes==0&&down.status().batches().isEmpty());
  var stopped=new WorkflowMetricStreamSmoke.Fixture();stopped.stopDuringRead=true;stopped.command(WorkflowMetricStream.Operation.START,0);stopped.time.advance(11);stopped.tick();check(stopped.f.store.transaction(stopped.f.p.tenantId(),s->s.diagnosticCount(stopped.f.p.subjectId().value()))==0);
  var f=new WorkflowMetricOutputSmoke.Fixture();var recorder=new WorkflowDiagnosticRecorder(f.store,Clock.fixed(NOW,ZoneOffset.UTC));var ref=new WorkflowQuality.Reference(f.d.id(),1,f.d.digest());var calls=new int[]{0};
  for(var cause:WorkflowDiagnostics.SourceFailure.values()){
   var expected=switch(cause){case UNAVAILABLE->new WorkflowFailure(WorkflowFailure.Code.SOURCE_UNAVAILABLE);case ACCESS_DENIED->new WorkflowFailure(WorkflowFailure.Code.AUTHORIZATION_REVOKED);case INCOMPLETE_WINDOW->new WorkflowFailure(WorkflowFailure.Code.WINDOW_INCOMPLETE);case INVALID_RESPONSE->new WorkflowFailure(WorkflowFailure.Code.INVALID_SAMPLE);case OTHER_FAILURE->new WorkflowFailure(WorkflowFailure.Code.SOURCE_WINDOW_CHANGED);default->WorkflowFailure.sourceChanged(cause);};
   var error=fail(()->recorder.inspectSource(f.p.tenantId(),f.p.subjectId().value(),ref,WorkflowQuality.Kind.METRIC_STREAM,1,FROM,TILL,null,s->true,(inspection,at)->inspection.source().read(()->{calls[0]++;throw expected;})));
   check(error==expected);var stored=f.store.transaction(f.p.tenantId(),s->s.diagnostics(f.p.subjectId().value(),f.d.id(),1,f.d.digest())).stream().map(WorkflowDiagnostics.Stored::observation).filter(v->v.error().equals(expected.code().name())&&v.sourceRead().failureCode()==cause).findFirst().orElseThrow();check(stored.state().equals("SOURCE_FAILED")&&stored.result().received()==null);check(stored.sourceRead().attempts()==1&&stored.sourceRead().failed()==1&&stored.sourceRead().received()==null);
  }check(calls[0]==9);
  var source=new WorkflowDiagnostics.SourceRead(1,1,0,2,null,NOW,NOW);invalid(()->new WorkflowDiagnostics.SourceRead(1,0,1,0,WorkflowDiagnostics.SourceFailure.UNAVAILABLE,NOW,NOW));invalid(()->new WorkflowDiagnostics.SourceRead(2,1,1,null,WorkflowDiagnostics.SourceFailure.UNAVAILABLE,NOW,NOW));invalid(()->new WorkflowDiagnostics.SourceRead(1,1,0,0,WorkflowDiagnostics.SourceFailure.UNIT_CHANGED,NOW,NOW));invalid(()->new WorkflowDiagnostics.SourceRead(1,1,0,0,null,NOW,NOW.minusNanos(1)));
  var old=new WorkflowDiagnostics.Observation(UUID.randomUUID(),ref,WorkflowQuality.Kind.METRIC_STREAM,1,"SOURCE_FAILED",FROM,TILL,NOW,NOW,null,null,"SOURCE_UNAVAILABLE",WorkflowDiagnostics.Result.unavailable());check(old.sourceRead()==null);
  invalid(()->new WorkflowDiagnostics.Observation(old.id(),ref,old.kind(),1,old.state(),FROM,TILL,NOW,NOW,null,null,"SOURCE_UNAVAILABLE",old.result(),new WorkflowDiagnostics.SourceRead(1,0,1,null,WorkflowDiagnostics.SourceFailure.UNIT_CHANGED,NOW,NOW)));
  // Two metadata checks reject drift before any output, including drift after history is fetched.
  for(var reason:List.of(WorkflowDiagnostics.SourceFailure.METADATA_CHANGED,WorkflowDiagnostics.SourceFailure.SOURCE_KEY_CHANGED,WorkflowDiagnostics.SourceFailure.VALUE_TYPE_CHANGED,WorkflowDiagnostics.SourceFailure.UNIT_CHANGED))for(int driftAt:List.of(1,2)){
   var pin=f.d.source().metric();var count=new int[2];var transport=new ZabbixJsonRpcConnector.Transport(){public String exchange(URI endpoint,String body,String token){if(body.contains("item.get")){count[0]++;return "metadata";}count[1]++;return "history";}public List<Map<String,Object>> readHostArray(String body){if(body.equals("history"))return List.of();var row=new HashMap<String,Object>(Map.of("itemid",pin.itemId(),"hostid",pin.hostId(),"key_",pin.sourceKey(),"units",pin.sourceUnit(),"value_type","0"));if(count[0]>=driftAt)switch(reason){case METADATA_CHANGED->row.put("hostid","other");case SOURCE_KEY_CHANGED->row.put("key_","changed.key");case VALUE_TYPE_CHANGED->row.put("value_type","3");case UNIT_CHANGED->row.put("units","seconds");default->throw new AssertionError();}return List.of(row);}};
   var error=fail(()->new ZabbixWorkflowMetricReader(()->0).readWindow(URI.create("http://127.0.0.1:18088/api_jsonrpc.php"),transport,"fixture-only",pin,FROM,TILL,NOW));check(error.code()==WorkflowFailure.Code.SOURCE_CHANGED&&error.sourceFailure()==reason);check(count[0]==driftAt&&count[1]==driftAt-1);
  }
  System.out.println("WorkflowSourceDiagnosticsSmoke: "+checks+" checks passed");
 }
}
