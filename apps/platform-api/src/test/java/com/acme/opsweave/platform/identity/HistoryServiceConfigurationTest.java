package com.acme.opsweave.platform.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class HistoryServiceConfigurationTest {
    @TempDir Path directory;
    static final JsonMapper JSON = JsonMapper.builder().build();
    HistoryServiceSettings settings(Path file) { return new HistoryServiceSettings(true,"https://identity.example.invalid","https://identity.example.invalid/jwks",file.toString(),false); }
    Map<String,Object> example() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("contracts/history-service-grants.json")) { return JSON.readValue(input,Map.class); }
    }
    @Test void endpointsAndLocalTestBindAreExplicit() {
        var file = directory.resolve("grants.json"); settings(file).validate("127.0.0.1");
        for (String endpoint : List.of("http://remote.invalid", "http://127.0.0.1:1234", "https://id.invalid/a/../b", "https://user@id.invalid", "https://id.invalid?x=y", "https://id.invalid#fragment"))
            assertThrows(IllegalStateException.class, () -> new HistoryServiceSettings(true,endpoint,endpoint,file.toString(),false).validate("127.0.0.1"));
        var local = new HistoryServiceSettings(true,"http://127.0.0.1:1234","http://127.0.0.1:1234/jwks",file.toString(),true);
        local.validate("127.0.0.1"); assertThrows(IllegalStateException.class, () -> local.validate("0.0.0.0"));
        assertThrows(IllegalStateException.class, () -> new HistoryServiceSettings(true,"https://id.invalid","https://id.invalid/jwks","relative.json",false).validate("127.0.0.1"));
    }
    @Test void grantFileRejectsUnknownDuplicateOversizedAndNonFileInputs() throws Exception {
        var file = directory.resolve("grants.json"); var document = example(); Files.writeString(file,JSON.writeValueAsString(document));
        var store = new FileHistoryServiceGrants(settings(file)); assertEquals("tenant-demo",store.find("opsweave-history-worker").identity().principal().tenantId().value());
        for (String contents : List.of("{}", "{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\",\"grants\":[]}", "x".repeat(262145))) {
            Files.writeString(file,contents); assertThrows(IllegalStateException.class, () -> store.find("opsweave-history-worker"));
        }
        Files.delete(file); assertThrows(IllegalStateException.class, () -> store.find("opsweave-history-worker"));
        assertThrows(IllegalStateException.class, () -> new FileHistoryServiceGrants(settings(directory)));
    }
    @Test void grantSemanticBoundsAndIdentityUniquenessAreEnforced() throws Exception {
        var file = directory.resolve("grants.json");
        for (var change : Map.<String,Object>ofEntries(Map.entry("issuer","https://other.invalid"),Map.entry("validUntil","2026-09-24T00:00:00Z"),Map.entry("validUntilOverlong","2027-09-25T00:00:00Z"),
            Map.entry("entityIds",List.of("*")),Map.entry("metricKeys",List.of("*")),Map.entry("itemIds",List.of("20001","20001")),Map.entry("from",-1),Map.entry("till",1799995600L),Map.entry("maxPoints",501),Map.entry("maxWindowSeconds",3601),Map.entry("requestsPerMinute",121),Map.entry("permissions",List.of("shell"))).entrySet()) {
            var document = example(); var row = (Map<String,Object>)((List<?>)document.get("grants")).getFirst(); row.put(change.getKey().equals("validUntilOverlong") ? "validUntil" : change.getKey(),change.getValue());
            Files.writeString(file,JSON.writeValueAsString(document)); assertThrows(IllegalStateException.class, () -> new FileHistoryServiceGrants(settings(file)),change.getKey());
        }
        var document = example(); var row = ((List<?>)document.get("grants")).getFirst(); document.put("grants",List.of(row,row)); Files.writeString(file,JSON.writeValueAsString(document));
        assertThrows(IllegalStateException.class, () -> new FileHistoryServiceGrants(settings(file)));
    }
}
