package com.acme.opsweave.platform.ai;

import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.file.*;
import java.util.*;
import static com.acme.opsweave.platform.ai.ModelSpendJson.*;

/** Operator-owned configuration, re-read on each operation and immediately before commit. Disabled by default. */
public final class FileAiRetentionPolicies {
    private final String file;
    public FileAiRetentionPolicies(String file){this.file=file==null?"":file;}
    public AiRetention.Policy require(TenantId tenant) {
        try {
            if(file.isBlank())throw new IllegalArgumentException();var path=Path.of(file);
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException();
            byte[] bytes;try(var input=Files.newInputStream(path)){bytes=input.readNBytes(262145);}if(bytes.length==0 || bytes.length>262144)throw new IllegalArgumentException();
            var n=AiRetentionJson.JSON.readTree(bytes);exact(n,"schemaVersion","policies");
            if(!text(n,"schemaVersion").equals("1.0") || !n.get("policies").isArray() || n.get("policies").size()>100)throw new IllegalArgumentException();
            var policies=new HashMap<TenantId,AiRetention.Policy>();for(var row:n.get("policies")){var p=AiRetentionJson.policy(row);if(policies.putIfAbsent(p.tenantId(),p)!=null)throw new IllegalArgumentException();}
            var result=policies.get(tenant);if(result==null)throw new IllegalArgumentException();return result;
        }catch(Exception invalid){throw new ToolFailure(ToolFailure.Code.UNAVAILABLE);}
    }
    public void unchanged(AiRetention.Policy expected){if(!require(expected.tenantId()).digest().equals(expected.digest()))throw new ToolFailure(ToolFailure.Code.INPUT_CHANGED);}
}
