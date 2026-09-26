package com.acme.opsweave.platform;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.acme.opsweave.identity.api.PrincipalContext;
import com.acme.opsweave.identity.api.AuthorizationService;
import com.acme.opsweave.identity.domain.*;
import com.acme.opsweave.inventory.application.SourceReviewService;
import com.acme.opsweave.platform.inventory.SourceSnapshotController;
import com.acme.opsweave.platform.persistence.InventoryWiring;
import com.acme.opsweave.sharedkernel.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SourceSnapshotBoundaryTest {
    @Test void restrictedObjectScopeCannotReconcileWholeSourceEvenWithEveryRequiredPermission(){
        var tenant=new TenantId("scope-fixture");var principals=mock(PrincipalContext.class);var authorization=mock(AuthorizationService.class);var wiring=mock(InventoryWiring.class);
        var controller=new SourceSnapshotController(principals,authorization,null,wiring,"cmdb-import","enterprise-assets");
        when(principals.requirePrincipal()).thenReturn(new Principal(new SubjectId("operator"),tenant,Set.of(Permission.ENTITY_READ,Permission.ENTITY_MANAGE,Permission.SOURCE_SYNC),ResourceScope.of(Set.of(ResourceRef.source(tenant,"cmdb-import"),ResourceRef.entity(tenant,EntityId.parse("11111111-1111-4111-8111-111111111111"))))));
        assertEquals(403,assertThrows(SourceReviewService.Access.class,()->controller.config(new MockHttpServletRequest())).status());
        assertEquals(403,assertThrows(SourceReviewService.Access.class,()->controller.correct(new MockHttpServletRequest())).status());
        assertEquals(403,assertThrows(SourceReviewService.Access.class,()->controller.correction(UUID.randomUUID().toString(),new MockHttpServletRequest())).status());
        assertEquals(403,assertThrows(SourceReviewService.Access.class,()->controller.corrections(UUID.randomUUID().toString(),new MockHttpServletRequest())).status());verifyNoInteractions(wiring,authorization);
    }
}
