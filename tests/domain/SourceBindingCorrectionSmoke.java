import com.acme.opsweave.inventory.domain.*;
import com.acme.opsweave.sharedkernel.*;
import java.time.*;
import java.util.*;

public class SourceBindingCorrectionSmoke {
    static int checks;static final UUID request=UUID.randomUUID(),oldSnapshot=UUID.randomUUID();
    static final EntityId previous=new EntityId(UUID.randomUUID()),target=new EntityId(UUID.randomUUID());
    static final AssetIdentity.Pin oldPin=new AssetIdentity.Pin(UUID.randomUUID(),"assets",UUID.randomUUID().toString(),1),pin=new AssetIdentity.Pin(UUID.randomUUID(),"assets",UUID.randomUUID().toString(),1);
    static final Instant at=Instant.parse("2026-09-26T00:00:00Z");static final TenantId tenant=new TenantId("tenant-domain");
    static final SourceSnapshot.Presence before=new SourceSnapshot.Presence(tenant,previous,"cmdb","external",oldPin,at.minusSeconds(1),at.minusSeconds(1),true,oldSnapshot);
    static SourceBindingCorrection.Command command(long a,long b,String reason){return new SourceBindingCorrection.Command(request,"external",oldSnapshot,previous,a,target,b,pin,at,Map.of("owner","New team"),reason);}
    static void yes(boolean value){checks++;if(!value)throw new AssertionError();}
    static void rejects(Runnable r){checks++;try{r.run();throw new AssertionError("accepted invalid correction");}catch(IllegalArgumentException|SourceBindingCorrection.Conflict expected){}}
    public static void main(String[] args){
        var c=command(2,5,"Verified source record");c.requireCurrent(before,"assets",2,5);yes(!c.input().complete());yes(c.input().records().size()==1);yes(c.input().records().getFirst().assetUuid().equals(pin.value()));
        rejects(()->command(0,5,"reason"));rejects(()->command(2,9_007_199_254_740_991L,"reason"));rejects(()->command(2,5," "));rejects(()->command(2,5,"bad\nreason"));
        rejects(()->c.requireCurrent(before,"other",2,5));rejects(()->c.requireCurrent(before,"assets",3,5));rejects(()->c.requireCurrent(before,"assets",2,6));
        rejects(()->new SourceBindingCorrection.Command(request,"external",oldSnapshot,previous,2,previous,3,pin,at,Map.of("owner","x"),"reason"));
        var noop=new SourceBindingCorrection.Command(request,"external",oldSnapshot,previous,2,previous,2,oldPin,at,Map.of("owner","x"),"reason");rejects(()->noop.requireCurrent(before,"assets",2,2));
        var input=c.input();var resolved=new SourceSnapshot.Resolved("external",target,pin,UUID.randomUUID(),6);
        var snapshot=new SourceSnapshot.Receipt(tenant,"operator","cmdb","assets",input,at,"sha256:"+"a".repeat(64),List.of(resolved),0);
        var receipt=new SourceBindingCorrection.Receipt(tenant,"operator","cmdb","assets",c,before,snapshot,3);yes(receipt.previous().entityId().equals(previous));
        rejects(()->new SourceBindingCorrection.Receipt(tenant,"other","cmdb","assets",c,before,snapshot,3));rejects(()->new SourceBindingCorrection.Receipt(tenant,"operator","cmdb","assets",c,before,snapshot,4));
        System.out.println("SourceBindingCorrectionSmoke: "+checks+" checks passed");
    }
}
