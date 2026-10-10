package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.inventory.api.RelationStore;
import com.acme.opsweave.inventory.domain.EntityRelation;
import com.acme.opsweave.inventory.domain.RelationConfirmation;
import com.acme.opsweave.inventory.domain.RelationCorrection;
import java.util.*;

final class RelationJson {
    private RelationJson() {}
    static Map<String,Object> wire(EntityRelation r) {
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion","1.0");m.put("id",r.id().toString());m.put("tenantId",r.tenantId().value());
        m.put("fromEntityId",r.fromEntityId().value().toString());m.put("toEntityId",r.toEntityId().value().toString());m.put("relationType",r.relationType());m.put("relationRevision",r.relationRevision());
        m.put("validFrom",r.validFrom().toString());m.put("validTo",r.validTo()==null?null:r.validTo().toString());m.put("dataMode",r.dataMode());m.put("version",r.version());return m;
    }
    static Map<String,Object> historyWire(EntityRelation r) {
        var m=new LinkedHashMap<String,Object>();m.put("relation",wire(r));m.put("closeReason",r.closeReason());return m;
    }
    static Map<String,Object> receipt(RelationStore.WriteResult result,UUID requestId){return Map.of("schemaVersion","1.0","requestId",requestId.toString(),"replayed",result.replayed(),"relation",wire(result.relation()));}
    static Map<String,Object> correctionReceipt(RelationStore.WriteResult result,UUID previousRelationId,UUID requestId){
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion","1.0");m.put("requestId",requestId.toString());m.put("replayed",result.replayed());
        m.put("previousRelationId",previousRelationId.toString());m.put("relation",wire(result.relation()));return m;
    }
    static Map<String,Object> correctionRecord(String tenantId,RelationCorrection record){
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion","1.0");m.put("tenantId",tenantId);m.put("requestId",record.requestId().toString());
        m.put("previousRelationId",record.previousRelationId().toString());m.put("relationId",record.relationId().toString());
        m.put("reason",record.reason());m.put("expectedVersion",record.expectedVersion());m.put("correctedAt",record.correctedAt().toString());return m;
    }
    static Map<String,Object> confirmationEntry(RelationConfirmation entry){
        var m=new LinkedHashMap<String,Object>();m.put("sourceKind",entry.key().sourceKind());m.put("sourceKey",entry.key().sourceKey());m.put("batchRef",entry.key().batchRef());
        m.put("dataMode",entry.dataMode());m.put("state",entry.withdrawn()?"WITHDRAWN":"CONFIRMED");m.put("confirmedAt",entry.confirmedAt().toString());
        m.put("withdrawnAt",entry.withdrawnAt()==null?null:entry.withdrawnAt().toString());m.put("withdrawReason",entry.withdrawReason());return m;
    }
    static Map<String,Object> confirmationReceipt(RelationStore.ConfirmationResult result,UUID relationId,UUID requestId){
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion","1.0");m.put("requestId",requestId.toString());m.put("replayed",result.replayed());
        m.put("relationId",relationId.toString());m.put("entry",confirmationEntry(result.entry()));return m;
    }
    static Map<String,Object> confirmationView(String storage,String tenantId,EntityRelation relation,List<RelationConfirmation> entries){
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion","1.0");m.put("storage",storage);m.put("tenantId",tenantId);
        m.put("relationId",relation.id().toString());m.put("relationState",relation.validTo()==null?"ACTIVE":"TERMINATED");
        m.put("entries",entries.stream().map(RelationJson::confirmationEntry).toList());return m;
    }
}
