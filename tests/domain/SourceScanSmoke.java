import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.application.IngestZabbixHostsUseCase;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.integration.infrastructure.*;
import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.inventory.infrastructure.InMemoryInventoryStore;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class SourceScanSmoke {
    static int checks;
    static final TenantId TENANT = new TenantId("scan-fixture");
    static final SourceScan.Scope SCOPE = new SourceScan.Scope(TENANT,"zabbix-1","host");
    static final Principal ACTOR = new Principal(new SubjectId("operator"),TENANT,Set.of(Permission.SOURCE_SYNC),ResourceScope.tenantWide());
    static void check(boolean ok) { checks++; if(!ok)throw new AssertionError("Check "+checks); }
    static void fails(SourceScan.Code code,Runnable action) { checks++;try {action.run();throw new AssertionError("Expected "+code);}catch(SourceScan.Failure e){if(e.code()!=code)throw new AssertionError(e);} }
    static final class Time extends Clock {
        volatile Instant now=Instant.parse("2026-09-26T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
        void advance(long seconds){now=now.plusSeconds(seconds);}
    }
    public static void main(String[] args)throws Exception {
        var time=new Time();var store=new InMemoryInventoryStore(time);
        var old=store.beginScan(SCOPE,UUID.randomUUID());
        fails(SourceScan.Code.BUSY,()->store.beginScan(SCOPE,UUID.randomUUID()));
        var other=store.beginScan(new SourceScan.Scope(new TenantId("other"),"zabbix-1","host"),UUID.randomUUID());
        var source=store.beginScan(new SourceScan.Scope(TENANT,"zabbix-2","host"),UUID.randomUUID());
        var type=store.beginScan(new SourceScan.Scope(TENANT,"zabbix-1","item"),UUID.randomUUID());
        check(other.fence()==1 && source.fence()==1 && type.fence()==1);
        time.advance(29);store.renewScan(old);time.advance(29);store.renewScan(old);
        check(old.deadlineAt().equals(time.instant().minusSeconds(58).plusSeconds(300)));
        time.advance(30);
        fails(SourceScan.Code.LOST,()->store.renewScan(old));
        fails(SourceScan.Code.BUSY,()->store.retireMissing(TENANT,"zabbix-1","host",Set.of()));
        fails(SourceScan.Code.LOST,()->store.beginScan(SCOPE,old.runId()));
        var next=store.beginScan(SCOPE,UUID.randomUUID());check(next.fence()==old.fence()+1);
        var host=host("1",time.instant());
        store.upsert(next,host.entity(),host.observation(),host.link());
        fails(SourceScan.Code.BUSY,()->store.upsert(host.entity(),host.observation(),host.link()));
        fails(SourceScan.Code.LOST,()->store.upsert(old,host.entity(),host.observation(),host.link()));
        fails(SourceScan.Code.LOST,()->store.finishScan(old,Set.of()));
        fails(SourceScan.Code.LOST,()->store.upsert(source,host.entity(),host.observation(),host.link()));
        store.releaseScan(old);store.renewScan(next);check(store.finishScan(next,Set.of("1"))==0);
        fails(SourceScan.Code.LOST,()->store.finishScan(next,Set.of()));
        check(store.find(TENANT,host.entity().id()).orElseThrow().lifecycle().equals("ACTIVE"));
        var bounded=store.beginScan(SCOPE,UUID.randomUUID());
        for(int i=0;i<10;i++){time.advance(29);store.renewScan(bounded);}
        time.advance(10);fails(SourceScan.Code.DEADLINE,()->store.renewScan(bounded));
        fails(SourceScan.Code.DEADLINE,()->store.finishScan(bounded,Set.of()));
        var max=new SourceScan.Token(SCOPE,UUID.randomUUID(),9_007_199_254_740_991L,time.instant(),time.instant().plusSeconds(300));
        fails(SourceScan.Code.LIMIT,()->SourceScan.Lease.acquire(SCOPE,UUID.randomUUID(),new SourceScan.Lease(max,time.instant().plusSeconds(30),true),time.instant()));
        delayedScan(false);delayedScan(true);
        System.out.println("SourceScanSmoke: "+checks+" checks passed");
    }
    static ZabbixHostMapper.MappedHost host(String id,Instant now){return new ZabbixHostMapper().map(PipelineDefinition.zabbixHostV1(),TENANT,"zabbix-1",Map.of("hostid",id,"host","host-"+id,"status","0"),now,now,"raw-"+UUID.randomUUID());}
    static IngestZabbixHostsUseCase ingest(Connector connector,InMemoryInventoryStore store,InMemorySyncRunStore runs){return new IngestZabbixHostsUseCase(new AuthorizeUseCase(),connector,store,new InMemoryRawRecordStore(),runs,new InMemoryPipelineVersionStore(),"labeled-fixture","memory","zabbix-1","env:OPSWEAVE_ZABBIX_TOKEN",1);}
    static void delayedScan(boolean empty)throws Exception {
        var time=new Time();var store=new InMemoryInventoryStore(time);var runs=new InMemorySyncRunStore();
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);var calls=new AtomicInteger();
        Connector delayed=new Connector(){
            public String type(){return "zabbix";}public ProbeResult probe(SourceContext c){return new ProbeResult(false,"test");}
            public Page fetch(SourceContext c,String cursor,int limit){calls.incrementAndGet();entered.countDown();try{if(!resume.await(10,TimeUnit.SECONDS))throw new AssertionError("Test timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return empty?new Page(List.of(),null,true):new FixtureZabbixHostConnector().fetch(c,cursor,limit);}
        };
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(()->ingest(delayed,store,runs).execute(ACTOR,null));
            try {
                check(entered.await(5,TimeUnit.SECONDS));
                var busy=ingest(delayed,store,runs).execute(ACTOR,null);check(busy.reasonCode().equals("SOURCE_SCAN_BUSY"));check(calls.get()==1);
                time.advance(30);
                var current=ingest(new FixtureZabbixHostConnector(),store,runs).execute(ACTOR,null);check(current.snapshotComplete() && current.accepted()==2);
                resume.countDown();var stale=pending.get(5,TimeUnit.SECONDS);
                check(stale.reasonCode().equals("SOURCE_SCAN_LOST") && !stale.snapshotComplete() && stale.accepted()==0);
                check(runs.find(TENANT,stale.syncRunId()).orElseThrow().status()==SyncStatus.FAILED);
                check(store.list(TENANT).size()==2 && store.list(TENANT).stream().allMatch(e->e.lifecycle().equals("ACTIVE")));
                check(ingest(new FixtureZabbixHostConnector(),store,runs).execute(ACTOR,null).snapshotComplete());
            } finally {resume.countDown();}
        }
    }
}
