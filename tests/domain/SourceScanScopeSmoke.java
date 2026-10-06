import com.acme.opsweave.inventory.domain.SourceScan;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.UUID;

public final class SourceScanScopeSmoke {
    public static void main(String[] args) {
        var tenant = new TenantId("scope-smoke");
        var digest = "sha256:" + "a".repeat(64);
        var first = new SourceScan.Scope(tenant, "connection-source", "item", digest);
        var second = new SourceScan.Scope(tenant, "connection-source", "item", "sha256:" + "b".repeat(64));
        if (!first.sameLeaseScope(second) || first.equals(second)) throw new AssertionError("physical and pinned scope must be distinct");
        var token = SourceScan.Lease.acquire(first, UUID.randomUUID(), null, Instant.parse("2026-10-07T00:00:00Z")).token();
        var changed = SourceScan.Lease.acquire(second, UUID.randomUUID(), new SourceScan.Lease(token, Instant.parse("2026-10-07T00:00:01Z"), false), Instant.parse("2026-10-07T00:01:00Z"));
        if (changed.token().scope().scopeDigest() == null || !changed.token().scope().scopeDigest().equals(second.scopeDigest())) throw new AssertionError("new pin not fenced");
        System.out.println("source-scan-scope: 3 checks passed");
    }
}
