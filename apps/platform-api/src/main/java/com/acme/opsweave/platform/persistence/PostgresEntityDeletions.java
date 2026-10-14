package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.inventory.api.EntityDeletionStore;
import com.acme.opsweave.inventory.domain.EntityDeletion;
import com.acme.opsweave.sharedkernel.EntityId;
import com.acme.opsweave.sharedkernel.TenantId;
import com.acme.opsweave.platform.inventory.EntityDeletionJson;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL backing for the operational delete entry. The soft delete is a lifecycle CAS, so the row
 * and every Incident/Evidence reference stay exactly where they were; only the lifecycle changes.
 */
final class PostgresEntityDeletions implements EntityDeletionStore {
    private final DataSource source;

    PostgresEntityDeletions(DataSource source) { this.source = source; }

    @Override
    public boolean protectedTarget(TenantId tenant, EntityId id) {
        try (var c = source.getConnection();
             var s = c.prepareStatement("SELECT EXISTS(SELECT 1 FROM inventory.entity_relation r"
                 + " WHERE r.tenant_id=? AND (r.from_entity_id=? OR r.to_entity_id=?) AND r.valid_to IS NULL)")) {
            s.setString(1, tenant.value());
            s.setObject(2, id.value());
            s.setObject(3, id.value());
            try (var r = s.executeQuery()) {
                return r.next() && r.getBoolean(1);
            }
        } catch (SQLException failed) {
            throw unavailable();
        }
    }

    @Override
    public boolean softDelete(TenantId tenant, EntityId id, long expectedVersion) {
        try (var c = source.getConnection();
             var s = c.prepareStatement("UPDATE inventory.entity SET lifecycle='DELETED', version=version+1"
                 + " WHERE tenant_id=? AND id=? AND version=?")) {
            s.setString(1, tenant.value());
            s.setObject(2, id.value());
            s.setLong(3, expectedVersion);
            return s.executeUpdate() == 1;
        } catch (SQLException failed) {
            throw unavailable();
        }
    }

    @Override
    public Optional<EntityDeletion.Receipt> command(TenantId tenant, UUID requestId) {
        try (var c = source.getConnection();
             var s = c.prepareStatement("SELECT body FROM inventory.entity_deletion_command WHERE tenant_id=? AND request_id=?")) {
            s.setString(1, tenant.value());
            s.setObject(2, requestId);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(EntityDeletionJson.receipt(r.getString("body"))) : Optional.empty();
            }
        } catch (SQLException | IllegalArgumentException failed) {
            throw unavailable();
        }
    }

    @Override
    public void addCommand(TenantId tenant, EntityDeletion.Receipt receipt) {
        try (var c = source.getConnection();
             var s = c.prepareStatement("INSERT INTO inventory.entity_deletion_command"
                 + " (tenant_id,request_id,entity_id,body,created_at) VALUES (?,?,?,?,?)")) {
            s.setString(1, tenant.value());
            s.setObject(2, receipt.requestId());
            s.setObject(3, receipt.entityId().value());
            s.setString(4, EntityDeletionJson.storage(receipt));
            s.setTimestamp(5, Timestamp.from(receipt.createdAt()));
            s.executeUpdate();
        } catch (SQLException failed) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Entity deletion store unavailable");
    }
}
