package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.platform.OpsweaveProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;

/** Rebuilding an adapter must not leak its real PostgreSQL pool across tests. */
abstract class OwnedInventoryTest {
    private final List<InventoryWiring> opened = new ArrayList<>();
    protected final InventoryWiring openInventory(OpsweaveProperties properties) { return openInventory(properties,""); }
    protected final InventoryWiring openInventory(OpsweaveProperties properties, String cmdbImportSource) { var value=InventoryWiring.open(properties,cmdbImportSource);opened.add(value);return value; }
    @AfterEach final void closeInventories() { for(var value:opened)value.close();opened.clear(); }
}
