package com.acme.opsweave.platform.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryErrorsTest {
    @Test void relationScanBudgetFailureIsExplicitlyUnavailable() {
        var response = new InventoryErrors().relationScanBudget(new com.acme.opsweave.inventory.api.RelationStore.ScanBudgetExceeded());
        assertEquals(503, response.getStatusCode().value());
        assertEquals(Map.of("error", "RELATION_SCAN_BUDGET_EXHAUSTED"), response.getBody());
    }

    @Test void relationAuthorizationFailureRemainsForbidden() {
        var response = new InventoryErrors().relationAccess(new com.acme.opsweave.inventory.api.RelationStore.Access("denied"));
        assertEquals(403, response.getStatusCode().value());
        assertEquals(Map.of("error", "FORBIDDEN"), response.getBody());
    }
}
