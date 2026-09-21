import com.acme.opsweave.identity.api.BearerCredentials;
import com.acme.opsweave.identity.application.AuthorizeUseCase;
import com.acme.opsweave.identity.domain.Authorizer;
import com.acme.opsweave.identity.domain.Permission;
import com.acme.opsweave.identity.domain.Principal;
import com.acme.opsweave.identity.domain.ResourceRef;
import com.acme.opsweave.identity.domain.ResourceScope;
import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.identity.infrastructure.DevPrincipalResolver;
import com.acme.opsweave.identity.infrastructure.OidcPrincipalResolver;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import java.util.Set;
import java.util.UUID;

public final class IdentityAuthorizationSmoke {
    public static void main(String[] args) {
        var tenant = new TenantId("tenant-demo");
        var other = new TenantId("tenant-other");
        var entity = new EntityId(UUID.fromString("d72a8c09-458b-4098-b9d5-89e2d58a7d4f"));
        var resource = ResourceRef.entity(tenant, entity);
        var authorizer = new Authorizer();
        var allowed = new Principal(
            new SubjectId("user-demo"),
            tenant,
            Set.of(Permission.ENTITY_READ),
            ResourceScope.tenantWide()
        );
        require(authorizer.decide(allowed, resource, Permission.ENTITY_READ).allowed(), "tenant-wide allow");
        require(
            !authorizer.decide(allowed, ResourceRef.entity(other, entity), Permission.ENTITY_READ).allowed(),
            "cross-tenant deny"
        );
        var noPerm = new Principal(new SubjectId("user-demo"), tenant, Set.of(), ResourceScope.tenantWide());
        require(!authorizer.decide(noPerm, resource, Permission.ENTITY_READ).allowed(), "missing permission deny");
        var scoped = new Principal(
            new SubjectId("user-demo"),
            tenant,
            Set.of(Permission.ENTITY_READ),
            ResourceScope.of(Set.of(resource))
        );
        require(authorizer.decide(scoped, resource, Permission.ENTITY_READ).allowed(), "explicit scope allow");
        var otherEntity = new EntityId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        require(
            !authorizer.decide(scoped, ResourceRef.entity(tenant, otherEntity), Permission.ENTITY_READ).allowed(),
            "explicit scope deny"
        );
        var useCase = new AuthorizeUseCase();
        require(useCase.authorize(allowed, resource, Permission.ENTITY_READ).allowed(), "application authorize allow");
        require(
            "PERMISSION_MISSING".equals(useCase.authorize(allowed, resource, Permission.SOURCE_SYNC).reasonCode()),
            "application authorize deny"
        );
        var resolver = new DevPrincipalResolver(
            "test-dev-token-please-do-not-use-elsewhere",
            new SubjectId("user-demo"),
            tenant,
            Set.of(Permission.ENTITY_READ),
            ResourceScope.tenantWide()
        );
        require(
            resolver.resolve(new BearerCredentials("test-dev-token-please-do-not-use-elsewhere")).isPresent(),
            "dev token allow"
        );
        require(resolver.resolve(new BearerCredentials("wrong-dev-token-please-do-not-use-elsewhere")).isEmpty(), "dev token deny");
        boolean oidcThrew = false;
        try {
            new OidcPrincipalResolver().resolve(new BearerCredentials("test-dev-token-please-do-not-use-elsewhere"));
        } catch (UnsupportedOperationException expected) {
            oidcThrew = true;
        }
        require(oidcThrew, "oidc placeholder must not mock");
        boolean shortTokenRejected = false;
        try {
            new DevPrincipalResolver(
                "too-short",
                new SubjectId("user-demo"),
                tenant,
                Set.of(Permission.ENTITY_READ),
                ResourceScope.tenantWide()
            );
        } catch (IllegalArgumentException expected) {
            shortTokenRejected = true;
        }
        require(shortTokenRejected, "short dev token rejected");
        System.out.println("Identity authorization smoke: 11 checks passed");
    }

    private static void require(boolean ok, String reason) {
        if (!ok) {
            throw new AssertionError(reason);
        }
    }
}
