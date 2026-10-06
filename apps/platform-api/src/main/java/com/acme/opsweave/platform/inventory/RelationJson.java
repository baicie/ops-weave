package com.acme.opsweave.platform.inventory;

import com.acme.opsweave.inventory.domain.EntityRelation;
import java.util.*;

final class RelationJson {
    private RelationJson() {}
    static Map<String,Object> wire(EntityRelation r) {
        var m=new LinkedHashMap<String,Object>();m.put("schemaVersion","1.0");m.put("id",r.id().toString());m.put("tenantId",r.tenantId().value());
        m.put("fromEntityId",r.fromEntityId().value().toString());m.put("toEntityId",r.toEntityId().value().toString());m.put("relationType",r.relationType());m.put("relationRevision",r.relationRevision());
        m.put("validFrom",r.validFrom().toString());m.put("validTo",r.validTo()==null?null:r.validTo().toString());m.put("dataMode",r.dataMode());m.put("version",r.version());return m;
    }
    static Map<String,Object> receipt(com.acme.opsweave.inventory.api.RelationStore.WriteResult result,UUID requestId){return Map.of("schemaVersion","1.0","requestId",requestId.toString(),"replayed",result.replayed(),"relation",wire(result.relation()));}
}
