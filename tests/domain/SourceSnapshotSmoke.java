import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

public final class SourceSnapshotSmoke {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Check "+checks);}
    static void invalid(Runnable call){checks++;try{call.run();throw new AssertionError("Expected invalid snapshot");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        var now=Instant.parse("2026-09-26T00:00:00Z");var value="51111111-1111-4111-8111-111111111111";
        var row=new SourceSnapshot.Row("asset-1",value,Map.of("owner","owner"));
        var input=new SourceSnapshot.Input(UUID.randomUUID(),now,true,List.of(row));input.requireFresh(now);check(input.records().size()==1);
        invalid(()->new SourceSnapshot.Row("asset", "Default string",Map.of("name","same")));
        invalid(()->new SourceSnapshot.Row("asset",value,Map.of("tenantId","other")));
        invalid(()->new SourceSnapshot.Row("asset",value,Map.of()));
        invalid(()->new SourceSnapshot.Input(UUID.randomUUID(),now,false,List.of(row,row)));
        invalid(()->new SourceSnapshot.Input(UUID.randomUUID(),now,false,List.of(row,new SourceSnapshot.Row("asset-2",value,row.values()))));
        invalid(()->input.requireFresh(now.minusNanos(1)));invalid(()->input.requireFresh(now.plus(SourceSnapshot.MAX_AGE)));
        input.requireFresh(now.plus(SourceSnapshot.MAX_AGE).minusNanos(1));checks++;
        var p=new SourceSnapshot.Presence(new TenantId("snapshot"),EntityId.parse("11111111-1111-4111-8111-111111111111"),"cmdb-import","asset-1",new AssetIdentity.Pin(UUID.randomUUID(),"enterprise-assets",value,1),now,now,true,input.requestId());
        check(p.status(now,true).equals("PRESENT"));check(p.status(p.expiresAt(),true).equals("STALE"));check(p.status(now,false).equals("IDENTITY_REVOKED"));
        var absent=new SourceSnapshot.Presence(p.tenantId(),p.entityId(),p.sourceInstanceId(),p.externalId(),p.identity(),now,now,false,input.requestId());check(absent.status(now,true).equals("ABSENT"));check(absent.status(now,false).equals("IDENTITY_REVOKED"));
        check(absent.status(absent.expiresAt(),true).equals("STALE"));
        invalid(()->new SourceSnapshot.Presence(p.tenantId(),p.entityId(),p.sourceInstanceId(),p.externalId(),p.identity(),now.plusNanos(1),now,true,input.requestId()));
        System.out.println("SourceSnapshotSmoke: "+checks+" checks passed");
    }
}
