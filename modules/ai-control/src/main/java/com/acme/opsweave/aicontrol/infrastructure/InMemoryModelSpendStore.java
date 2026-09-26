package com.acme.opsweave.aicontrol.infrastructure;

import com.acme.opsweave.aicontrol.api.ModelSpendStore;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;

public final class InMemoryModelSpendStore implements ModelSpendStore {
    private record Key(TenantId tenant, UUID run) {}
    private final Map<Key,ModelSpend.Call> calls=new HashMap<>();
    @Override public synchronized ModelSpend.Call reserve(ModelSpend.Call call) {
        var key=new Key(call.tenantId(),call.runId()); if(calls.containsKey(key)) throw new ToolFailure(ToolFailure.Code.INPUT_CHANGED);
        long count=0,spent=0;
        for(var old:calls.values()) if(old.tenantId().equals(call.tenantId())) { count++; if(old.countsOn(call.day())) spent=Math.addExact(spent,old.chargedMicros()); }
        ModelSpend.admit(call,spent,count); calls.put(key,call); return call;
    }
    @Override public synchronized ModelSpend.Call report(TenantId tenant,SubjectId subject,UUID run,UUID session,ModelSpend.Usage usage,Instant now) {
        var key=new Key(tenant,run); var old=calls.get(key); if(old==null) throw new ToolFailure(ToolFailure.Code.NOT_FOUND);
        if(!old.subjectId().equals(subject) || !old.sessionId().equals(session)) throw new ToolFailure(ToolFailure.Code.FORBIDDEN);
        var result=old.report(usage,now); calls.put(key,result); return result;
    }
    @Override public synchronized Optional<ModelSpend.Call> find(TenantId tenant,UUID run) { return Optional.ofNullable(calls.get(new Key(tenant,run))); }
}
