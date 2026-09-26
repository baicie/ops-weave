import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.aicontrol.infrastructure.InMemoryModelSpendStore;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class ModelSpendSmoke {
    static int checks;static final Instant NOW=Instant.parse("2026-09-25T23:59:40Z");
    static final TenantId TENANT=new TenantId("spend-test");static final SubjectId USER=new SubjectId("operator");
    static final ModelSpend.Policy POLICY=new ModelSpend.Policy("rig-openai","fixture-model","fixture-rates-v1",2_000_000,10_000_000,184_320,200_000);
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Check "+checks);}
    static void fails(ToolFailure.Code code,Runnable task){checks++;try{task.run();throw new AssertionError("Expected "+code);}catch(ToolFailure failure){if(failure.code()!=code)throw new AssertionError(failure);}}
    static ModelSpend.Call call(TenantId tenant,ModelSpend.Policy policy,Instant now){return new ModelSpend.Call(tenant,USER,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"sha256:"+"a".repeat(64),1000,policy,now,now.plusSeconds(40),null,null);}
    public static void main(String[] args)throws Exception{
        check(POLICY.reservationMicros()==184_320);check(POLICY.estimate(100,10)==300);
        var fractional=new ModelSpend.Policy("rig-openai","fixture-model","fractions",1,1,1,1);check(fractional.estimate(1,1)==1);check(fractional.reservationMicros()==1);
        check(ModelSpend.Policy.mock().reservationMicros()==0);
        for(int invalid:new int[]{-1,81921})fails(ToolFailure.Code.INVALID_REQUEST,()->POLICY.estimate(invalid,1));
        fails(ToolFailure.Code.INVALID_REQUEST,()->POLICY.estimate(1,2049));
        fails(ToolFailure.Code.INVALID_REQUEST,()->new ModelSpend.Usage(100,5,101,"provider-reported"));
        fails(ToolFailure.Code.INVALID_REQUEST,()->new ModelSpend.Usage(0,0,0,"provider-reported"));
        fails(ToolFailure.Code.INVALID_REQUEST,()->new ModelSpend.Usage(1,0,0,"mock-no-call"));
        fails(ToolFailure.Code.INVALID_REQUEST,()->new ModelSpend.Policy("mock-deterministic","mock-current-v1","mock-no-charge",1,0,0,0));
        var store=new InMemoryModelSpendStore();var a=call(TENANT,POLICY,NOW);store.reserve(a);
        check(a.state(NOW).equals("RESERVED"));check(a.state(a.deadlineAt()).equals("UNCERTAIN"));check(a.chargedMicros()==184_320);
        fails(ToolFailure.Code.INPUT_CHANGED,()->store.reserve(a));
        fails(ToolFailure.Code.BUDGET_EXHAUSTED,()->store.reserve(call(TENANT,POLICY,NOW)));
        // UTC rollover and expiry cannot silently refund a possibly billed request.
        fails(ToolFailure.Code.BUDGET_EXHAUSTED,()->store.reserve(call(TENANT,POLICY,NOW.plusSeconds(86400))));
        check(store.reserve(call(new TenantId("other"),POLICY,NOW))!=null);
        var used=new ModelSpend.Usage(100,10,50,"provider-reported");
        fails(ToolFailure.Code.FORBIDDEN,()->store.report(TENANT,new SubjectId("other"),a.runId(),a.sessionId(),used,NOW.plusSeconds(1)));
        fails(ToolFailure.Code.FORBIDDEN,()->store.report(TENANT,USER,a.runId(),UUID.randomUUID(),used,NOW.plusSeconds(1)));
        check(store.find(TENANT,a.runId()).orElseThrow().usage()==null);
        var reported=store.report(TENANT,USER,a.runId(),a.sessionId(),used,NOW.plusSeconds(1));
        check(reported.chargedMicros()==300);check(reported.state(NOW.plusSeconds(2)).equals("REPORTED"));
        check(store.report(TENANT,USER,a.runId(),a.sessionId(),used,NOW.plusSeconds(3)).equals(reported));
        fails(ToolFailure.Code.INPUT_CHANGED,()->store.report(TENANT,USER,a.runId(),a.sessionId(),new ModelSpend.Usage(101,10,50,"provider-reported"),NOW.plusSeconds(2)));
        check(store.reserve(call(TENANT,POLICY,NOW))!=null);check(store.find(new TenantId("other"),a.runId()).isEmpty());
        var mock=call(new TenantId("mock"),ModelSpend.Policy.mock(),NOW);store.reserve(mock);
        check(store.report(mock.tenantId(),USER,mock.runId(),mock.sessionId(),new ModelSpend.Usage(0,0,0,"mock-no-call"),NOW).chargedMicros()==0);
        fails(ToolFailure.Code.INVALID_REQUEST,()->a.report(new ModelSpend.Usage(0,0,0,"mock-no-call"),NOW));
        var low=new ModelSpend.Policy("rig-openai","fixture-model","low",2_000_000,10_000_000,1,200_000);
        fails(ToolFailure.Code.BUDGET_EXHAUSTED,()->store.reserve(call(new TenantId("low"),low,NOW)));
        fails(ToolFailure.Code.BUDGET_EXHAUSTED,()->ModelSpend.admit(a,0,10_000));
        var concurrent=new InMemoryModelSpendStore();
        try(var pool=Executors.newFixedThreadPool(4)){
            var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{try{concurrent.reserve(call(TENANT,POLICY,NOW));return true;}catch(ToolFailure expected){if(expected.code()!=ToolFailure.Code.BUDGET_EXHAUSTED)throw expected;return false;}}));
            int won=0;for(var f:futures)if(f.get(5,TimeUnit.SECONDS))won++;check(won==1);
        }
        System.out.println("ModelSpendSmoke: "+checks+" checks passed");
    }
}
