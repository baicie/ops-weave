import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Set;

public final class IdentityGrantSmoke {
    static int checks;
    static void denied(Runnable call) { try { call.run(); throw new AssertionError("Expected rejection"); } catch (IllegalArgumentException expected) { checks++; } }
    public static void main(String[] args) {
        var principal = new Principal(new SubjectId("operator"), new TenantId("tenant"), Set.of(Permission.ENTITY_READ), ResourceScope.tenantWide());
        var grant = new IdentityGrant("https://issuer.invalid", "verified-sub", 1, true, principal);
        var now = Instant.parse("2026-09-25T00:00:00Z");
        if (grant.bind(grant.issuer(), grant.externalSubject(), now.plusNanos(1), now) != principal) throw new AssertionError("Must use operator authorization"); checks++;
        denied(() -> grant.bind("https://other.invalid", grant.externalSubject(), now.plusSeconds(1), now));
        denied(() -> grant.bind(grant.issuer(), "other-sub", now.plusSeconds(1), now));
        denied(() -> grant.bind(grant.issuer(), grant.externalSubject(), now, now));
        denied(() -> grant.bind(grant.issuer(), grant.externalSubject(), now.minusNanos(1), now));
        denied(() -> grant.bind(grant.issuer(), grant.externalSubject(), null, now));
        denied(() -> new IdentityGrant(grant.issuer(), grant.externalSubject(), 1, false, principal).bind(grant.issuer(), grant.externalSubject(), now.plusSeconds(1), now));
        denied(() -> new IdentityGrant(grant.issuer(), "bad\nsub", 1, true, principal));
        denied(() -> new IdentityGrant(grant.issuer(), " ", 1, true, principal));
        denied(() -> new IdentityGrant(grant.issuer(), "x".repeat(256), 1, true, principal));
        denied(() -> new IdentityGrant(grant.issuer(), grant.externalSubject(), 0, true, principal));
        denied(() -> new IdentityGrant("", grant.externalSubject(), 1, true, principal));
        System.out.println("IdentityGrantSmoke: " + checks + " checks passed");
    }
}
