package com.acme.opsweave.platform.workflow;

import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.integration.api.Connector;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.OpsweaveProperties;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostSourceInspectionReaderTest {
    private static final TenantId TENANT = new TenantId("inspection-reader-fixture");
    private static final Principal USER = new Principal(new SubjectId("owner"), TENANT, Set.of(Permission.SOURCE_SYNC), ResourceScope.tenantWide());
    private static final WorkflowDefinition.Source SOURCE = new WorkflowDefinition.Source("ZABBIX_HOST", "reader-fixture");
    private static final class Source implements Connector {
        int reads; int lastLimit; boolean reachable = true; boolean failRead;
        Page page = new Page(List.of(), null, true, SyncScan.HOSTID_WATERMARK);
        public String type() { return "ZABBIX_HOST"; }
        public ProbeResult probe(SourceContext context) { return new ProbeResult(reachable, reachable ? "READ_VERIFIED" : "UNREACHABLE", "7.0.0"); }
        public Page fetch(SourceContext context, String cursor, int limit) {
            assertEquals(TENANT, context.tenantId()); assertEquals(SOURCE.instanceId(), context.sourceInstanceId()); assertNull(cursor);
            reads++; lastLimit = limit;
            if (failRead) throw new IllegalStateException("Synthetic authorization refusal");
            return page;
        }
    }
    private static HostSourceInspectionReader reader(Source source, String mode) {
        return new HostSourceInspectionReader(source, new OpsweaveProperties(null, new OpsweaveProperties.Zabbix(mode, "http://127.0.0.1:18088/api_jsonrpc.php", "env:TEST_READER_REFERENCE", SOURCE.instanceId(), 5), null));
    }
    private static Connector.RawRecord record(Map<String,Object> fields) { return new Connector.RawRecord("synthetic", Instant.parse("2026-10-03T00:00:00Z"), fields); }
    @Test void anonymousVersionCannotReplaceAnAuthorizedRead() {
        var source = new Source(); source.failRead = true;
        assertThrows(IllegalStateException.class, () -> reader(source,"jsonrpc").read(USER,SOURCE,"TEST"));
        assertEquals(1,source.reads); assertEquals(1,source.lastLimit);
    }
    @Test void failedProbeNeverReadsAndNeverReturnsTheReportedVersion() {
        var source = new Source(); source.reachable = false;
        var result = reader(source,"jsonrpc").read(USER,SOURCE,"TEST");
        assertFalse(result.check().reachable()); assertEquals("UNREACHABLE",result.check().statusCode()); assertNull(result.check().reportedVersion()); assertEquals(0,source.reads);
    }
    @Test void offsetReadCannotClaimVerifiedMembership() {
        var source = new Source(); source.page = new Connector.Page(List.of(record(Map.of("hostid","101"))),null,true,SyncScan.OFFSET_ATTEMPT);
        var check = reader(source,"jsonrpc").read(USER,SOURCE,"TEST").check(); assertEquals("UNVERIFIED",check.statusCode());
        var result = reader(source,"jsonrpc").read(USER,SOURCE,"DISCOVER").discovery(); assertFalse(result.complete()); assertEquals("UNVERIFIED",result.scanConsistency()); assertEquals("INCOMPLETE",result.statusCode()); assertEquals(5,source.lastLimit);
    }
    @Test void partialPagePreservesMixedAndMissingTypesWithoutSourceValues() {
        var source = new Source(); source.page = new Connector.Page(List.of(record(Map.of("hostid","101","host","synthetic-private-host","name","Synthetic","status",0,"interfaces",List.of(Map.of("ip","192.0.2.1")))),record(Map.of("hostid",102,"host","synthetic-other","status",true))),"opaque-next",false,SyncScan.HOSTID_WATERMARK);
        var result = reader(source,"jsonrpc").read(USER,SOURCE,"DISCOVER").discovery();
        assertFalse(result.complete()); assertEquals(2,result.observedRecords()); assertEquals("HOSTID_WATERMARK",result.scanConsistency()); assertEquals("INCOMPLETE",result.statusCode());
        var fields = new HashMap<String,SourceInspection.Field>(); result.fields().forEach(f -> fields.put(f.name(),f));
        assertEquals("MIXED",fields.get("hostid").type()); assertEquals("MIXED",fields.get("status").type()); assertTrue(fields.get("name").nullable()); assertEquals("TEXT_ARRAY",fields.get("interfaces.ip").type()); assertTrue(fields.get("interfaces.ip").nullable());
        assertFalse(result.toString().contains("synthetic-private-host")); assertFalse(result.toString().contains("192.0.2.1")); assertEquals(SourceInspection.fieldDigest(result.fields()),result.fingerprint()); assertEquals(1,source.reads);
    }
    @Test void emptyVerifiedPageIsDistinctFromUnavailable() {
        var source = new Source(); var result = reader(source,"jsonrpc").read(USER,SOURCE,"DISCOVER").discovery(); assertTrue(result.complete()); assertEquals(0,result.observedRecords()); assertTrue(result.fields().isEmpty()); assertEquals("READ_VERIFIED",result.statusCode());
    }
    @Test void exceedingEitherReadBudgetFailsWithoutTruncatingToSuccess() {
        var source = new Source(); source.page = new Connector.Page(Collections.nCopies(6,record(Map.of("hostid","101"))),null,true,SyncScan.HOSTID_WATERMARK);
        assertThrows(IllegalStateException.class,() -> reader(source,"jsonrpc").read(USER,SOURCE,"TEST")); assertThrows(IllegalStateException.class,() -> reader(source,"jsonrpc").read(USER,SOURCE,"DISCOVER")); assertEquals(2,source.reads);
    }
    @Test void fixtureAndPermissionBoundariesRemainExplicit() {
        var source = new Source(); assertEquals("LABELED_FIXTURE",reader(source,"fixture").read(USER,SOURCE,"TEST").check().statusCode()); assertEquals("LABELED_FIXTURE",reader(source,"fixture").read(USER,SOURCE,"DISCOVER").discovery().scanConsistency());
        var denied = new Principal(USER.subjectId(),TENANT,Set.of(),ResourceScope.tenantWide()); var failure = assertThrows(WorkflowFailure.class,() -> reader(source,"fixture").read(denied,SOURCE,"DISCOVER")); assertEquals(WorkflowFailure.Code.FORBIDDEN,failure.code()); assertEquals(2,source.reads);
    }
}
