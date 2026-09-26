package com.acme.opsweave.inventory.api;
import com.acme.opsweave.inventory.domain.SourceSnapshot;
import com.acme.opsweave.inventory.domain.SourceBindingCorrection;
import com.acme.opsweave.sharedkernel.*;
import java.time.Instant;
import java.util.*;

public interface SourceSnapshotStore {
    SourceSnapshot.Receipt ingest(TenantId tenant,String actor,String source,String namespace,SourceSnapshot.Input input,String mappingDigest);
    Optional<SourceSnapshot.Receipt> receipt(TenantId tenant,String actor,String source,String namespace,UUID requestId);
    record State(SourceSnapshot.Presence presence, boolean identityActive) {}
    record Page(Instant evaluatedAt,List<State> items){public Page{items=List.copyOf(items);}}
    Page presence(TenantId tenant,EntityId entity,String source);
    SourceBindingCorrection.Receipt correct(TenantId tenant,String actor,String source,String namespace,SourceBindingCorrection.Command command,String mappingDigest);
    Optional<SourceBindingCorrection.Receipt> correction(TenantId tenant,String actor,String source,String namespace,UUID requestId);
    List<SourceBindingCorrection.Receipt> corrections(TenantId tenant,String source,String namespace,EntityId entity,UUID after,int limit);
}
