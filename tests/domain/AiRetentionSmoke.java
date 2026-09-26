import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

public final class AiRetentionSmoke {
    static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("Check "+checks);}static void fails(ToolFailure.Code code,Runnable f){checks++;try{f.run();throw new AssertionError("Expected "+code);}catch(ToolFailure e){if(e.code()!=code)throw new AssertionError(e);}}
    public static void main(String[] args){var tenant=new TenantId("retention-fixture");var actor=new SubjectId("operator");var now=Instant.parse("2026-09-26T12:00:00Z");var id=UUID.randomUUID();
        var p=new AiRetention.Policy(tenant,"v1",30,7,90,100,Set.of(id),true);check(!p.eligible(AiRetention.Kind.EVIDENCE,id,now.minusSeconds(8*86400),now.minusSeconds(1),now));
        check(!p.eligible(AiRetention.Kind.EVIDENCE,UUID.randomUUID(),now.minusSeconds(7*86400),now.minusSeconds(1),now));check(!p.eligible(AiRetention.Kind.EVIDENCE,UUID.randomUUID(),now.minusSeconds(8*86400),now,now));check(p.eligible(AiRetention.Kind.EVIDENCE,UUID.randomUUID(),now.minusSeconds(8*86400),now.minusSeconds(1),now));
        for(int n:new int[]{0,3651})fails(ToolFailure.Code.INVALID_REQUEST,()->new AiRetention.Policy(tenant,"v1",n,7,90,100,Set.of(),true));for(int n:new int[]{0,101})fails(ToolFailure.Code.INVALID_REQUEST,()->new AiRetention.Policy(tenant,"v1",30,7,90,n,Set.of(),true));
        check(!p.digest().equals(new AiRetention.Policy(tenant,"v1",30,7,90,100,Set.of(),true).digest()));check(!p.digest().equals(new AiRetention.Policy(tenant,"v1",30,7,90,100,Set.of(id),false).digest()));
        var batches=List.of(new AiRetention.Batch(AiRetention.Kind.INSIGHT,List.of(),0,false),new AiRetention.Batch(AiRetention.Kind.EVIDENCE,List.of(id),100,false),new AiRetention.Batch(AiRetention.Kind.AUDIT,List.of(),0,false));var v=new AiRetention.Preview(tenant,actor,p.digest(),now,batches);v.checkLive(now);v.checkLive(now.plusSeconds(119));checks+=2;fails(ToolFailure.Code.EXPIRED,()->v.checkLive(now.minusNanos(1)));fails(ToolFailure.Code.EXPIRED,()->v.checkLive(now.plusSeconds(120)));
        check(!v.digest().equals(new AiRetention.Preview(tenant,new SubjectId("other"),p.digest(),now,batches).digest()));check(!v.digest().equals(new AiRetention.Preview(new TenantId("other"),actor,p.digest(),now,batches).digest()));
        fails(ToolFailure.Code.INVALID_REQUEST,()->new AiRetention.Batch(AiRetention.Kind.EVIDENCE,List.of(id,id),100,false));fails(ToolFailure.Code.INVALID_REQUEST,()->new AiRetention.Preview(tenant,actor,p.digest(),now.plusNanos(1),batches));
        var cmd=new AiRetention.Command(UUID.randomUUID(),p.digest(),now,v.digest());check(new AiRetention.Receipt(tenant,actor,cmd,v,now).preview().equals(v));fails(ToolFailure.Code.INVALID_REQUEST,()->new AiRetention.Receipt(tenant,new SubjectId("other"),cmd,v,now));
        AiRetention.authorize(new Principal(actor,tenant,Set.of(Permission.AI_RETENTION_MANAGE),ResourceScope.tenantWide()));checks++;
        fails(ToolFailure.Code.FORBIDDEN,()->AiRetention.authorize(new Principal(actor,tenant,Set.of(Permission.AI_RETENTION_MANAGE),ResourceScope.of(Set.of(ResourceRef.incident(tenant,id))))));fails(ToolFailure.Code.FORBIDDEN,()->AiRetention.authorize(new Principal(actor,tenant,Set.of(Permission.AI_DIAGNOSE),ResourceScope.tenantWide())));
        var m=new AiRetention.Marker(AiRetention.Kind.EVIDENCE,tenant,id,UUID.randomUUID(),actor,UUID.randomUUID(),1,Set.of(),Set.of("cpu.usage"),now.minusSeconds(1),now,null);check(m.requestDigest()==null);
        System.out.println("AiRetentionSmoke: "+checks+" checks passed");
    }
}
