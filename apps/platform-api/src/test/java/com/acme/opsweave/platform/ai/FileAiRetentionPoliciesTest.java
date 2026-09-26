package com.acme.opsweave.platform.ai;
import static org.junit.jupiter.api.Assertions.*;
import com.acme.opsweave.aicontrol.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class FileAiRetentionPoliciesTest {
    @TempDir Path dir;final TenantId tenant=new TenantId("retention-fixture");
    @Test void defaultOffStrictFileAndLiveHoldRecheck()throws Exception{assertEquals(ToolFailure.Code.UNAVAILABLE,assertThrows(ToolFailure.class,()->new FileAiRetentionPolicies("").require(tenant)).code());var p=new AiRetention.Policy(tenant,"v1",30,7,90,100,Set.of(),true);var file=dir.resolve("policy.json");var raw=AiRetentionJson.JSON.writeValueAsString(Map.of("schemaVersion","1.0","policies",List.of(AiRetentionJson.policy(p))));Files.writeString(file,raw);var policies=new FileAiRetentionPolicies(file.toString());assertEquals(p,policies.require(tenant));Files.writeString(file,raw.replace("\"heldIncidents\":[]","\"heldIncidents\":[\""+UUID.randomUUID()+"\"]"));assertEquals(ToolFailure.Code.INPUT_CHANGED,assertThrows(ToolFailure.class,()->policies.unchanged(p)).code());for(var bad:List.of(raw+"{}",raw.replace("\"schemaVersion\":\"1.0\"","\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\""),raw.replace("\"batchSize\":100","\"batchSize\":101"),"x".repeat(262145))){Files.writeString(file,bad);assertThrows(ToolFailure.class,()->policies.require(tenant));}}
}
