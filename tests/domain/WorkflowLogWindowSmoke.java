import com.acme.opsweave.integration.domain.*;
import java.time.*;
import java.util.*;

/** Explicit domain fixtures. No external collection or storage is simulated as connected. */
public final class WorkflowLogWindowSmoke {
    static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("Log window "+checks);}
    static void rejects(Runnable work){checks++;try{work.run();}catch(IllegalArgumentException|WorkflowFailure expected){return;}throw new AssertionError("Invalid window accepted");}
    static final Instant FROM=Instant.parse("2026-10-04T00:00:00Z"),TILL=FROM.plusSeconds(60),NOW=TILL.plusSeconds(10);
    static WorkflowDefinition definition(boolean filter){
        var item=new SourceMetricDiscovery.Item("1","2","log[/fixture]","Synthetic Fixture","","LOG","NO_MAPPING",null);
        var sourceId=UUID.randomUUID();var source=new WorkflowDefinition.Source("ZABBIX_LOG",sourceId.toString(),new WorkflowDefinition.ConfigurationPin(sourceId,1,"sha256:"+"a".repeat(64)),null,WorkflowLogSourcePin.from(UUID.randomUUID(),item));
        var nodes=new ArrayList<WorkflowDefinition.Node>();nodes.add(new WorkflowDefinition.Node("source",WorkflowDefinition.Type.SOURCE,"1",Map.of()));
        nodes.add(new WorkflowDefinition.Node("mapping",WorkflowDefinition.Type.MAP,"1",filter?Map.of("timestamp","eventTime","body","body","severityCode","severityText"):Map.of("timestamp","eventTime","body","body")));
        if(filter)nodes.add(new WorkflowDefinition.Node("filter",WorkflowDefinition.Type.FILTER,"1",Map.of("field","severityText","equals","1")));
        nodes.add(new WorkflowDefinition.Node("validate",WorkflowDefinition.Type.VALIDATE,"1",Map.of()));nodes.add(new WorkflowDefinition.Node("output",WorkflowDefinition.Type.OUTPUT,"1",Map.of()));
        var edges=new ArrayList<WorkflowDefinition.Edge>();for(int i=1;i<nodes.size();i++)edges.add(new WorkflowDefinition.Edge(nodes.get(i-1).id(),nodes.get(i).id()));
        return WorkflowOperators.builtIn().pin(new WorkflowDefinition("log-window-fixture",1,"Synthetic Fixture",source,new WorkflowDefinition.Target(null,1,null,"LOG"),nodes,edges));
    }
    static Map<String,Object> row(int i){var row=new LinkedHashMap<String,Object>();row.put("timestamp",FROM.plusNanos(i).toString());row.put("body","  Synthetic Fixture "+i+"\n<script>  ");row.put("sourceKey","log[/fixture]");row.put("logEventTime",null);row.put("severityCode",Integer.toString(i%2));row.put("eventSource",null);row.put("eventId","0");return Collections.unmodifiableMap(row);}
    static WorkflowLogOutput.Scope scope(WorkflowDefinition d){return new WorkflowLogOutput.Scope("log-window-fixture","a".repeat(64),UUID.randomUUID(),d.id(),d.revision(),d.digest());}
    static WorkflowLogWindow.Batch prepare(WorkflowDefinition d,WorkflowLogOutput.Scope scope,List<Map<String,Object>> rows){return WorkflowLogWindow.prepare(scope,WorkflowOperators.builtIn().compile(d,null),rows,FROM,TILL,NOW);}
    public static void main(String[] args){
        var d=definition(false);var scope=scope(d);var rows=java.util.stream.IntStream.range(0,1000).mapToObj(WorkflowLogWindowSmoke::row).toList();var batch=prepare(d,scope,rows);
        check(batch.records().size()==1000&&batch.records().getLast().index()==999);check(batch.records().getLast().position().equals(FROM.plusNanos(999).toString()));check(batch.records().getFirst().body().equals(rows.getFirst().get("body")));check(batch.records().getFirst().severityText()==null);
        check(batch.equals(prepare(d,scope,rows)));check(batch.digest().equals(prepare(d,scope,rows).digest()));check(!batch.digest().equals(prepare(d,scope(d),rows).digest()));
        check(WorkflowLogWindow.inputDigest(d.source(),FROM,TILL,rows).equals(WorkflowLogWindow.inputDigest(d.source(),FROM,TILL,rows)));
        var rawChanged=new ArrayList<>(rows);var raw=new LinkedHashMap<>(row(999));raw.put("eventId","1");rawChanged.set(999,raw);check(!WorkflowLogWindow.inputDigest(d.source(),FROM,TILL,rows).equals(WorkflowLogWindow.inputDigest(d.source(),FROM,TILL,rawChanged)));
        var filtered=definition(true);var fb=prepare(filtered,scope(filtered),rows);check(fb.filtered()==500&&fb.records().size()==500);check(fb.records().getFirst().index()==1&&fb.records().getLast().index()==999);
        var empty=prepare(d,scope,List.of());check(empty.records().isEmpty()&&empty.inputCount()==0);var allFiltered=prepare(filtered,scope(filtered),List.of(row(0)));check(allFiltered.records().isEmpty()&&allFiltered.filtered()==1);
        var changed=new ArrayList<>(rows);var bad=new LinkedHashMap<>(row(999));bad.put("body"," ");changed.set(999,bad);rejects(()->prepare(d,scope,changed));
        rejects(()->prepare(d,scope,List.of(row(0),row(0))));rejects(()->prepare(d,scope,List.of(row(1),row(0))));rejects(()->prepare(d,scope,Collections.nCopies(1001,row(0))));
        var wrong=new LinkedHashMap<>(row(0));wrong.put("sourceKey","log[/other]");rejects(()->prepare(d,scope,List.of(wrong)));var outside=new LinkedHashMap<>(row(0));outside.put("timestamp",TILL.toString());rejects(()->prepare(d,scope,List.of(outside)));
        rejects(()->WorkflowLogWindow.requireWindow(FROM,TILL,NOW.minusSeconds(1)));rejects(()->WorkflowLogWindow.requireWindow(FROM,TILL,NOW.plusSeconds(86400)));
        rejects(()->new WorkflowLogOutput.Record(5,FROM.toString(),"Synthetic Fixture",null,null,null,null));
        var replacement=new ArrayList<>(batch.records());var old=replacement.get(1);replacement.set(1,new WorkflowLogWindow.Record(old.index(),old.position(),old.eventTime(),"Synthetic Fixture changed",null,null,null,null));check(!batch.digest().equals(new WorkflowLogWindow.Batch(scope,FROM,TILL,1000,0,replacement).digest()));
        System.out.println("WorkflowLogWindowSmoke: "+checks+" checks passed");
    }
}
