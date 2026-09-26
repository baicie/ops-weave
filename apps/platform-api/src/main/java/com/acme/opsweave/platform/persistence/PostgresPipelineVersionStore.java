package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.integration.api.PipelineVersionStore;
import com.acme.opsweave.integration.domain.PipelineException;
import com.acme.opsweave.integration.domain.PipelineException.Code;
import com.acme.opsweave.integration.domain.PipelineVersion;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

final class PostgresPipelineVersionStore implements PipelineVersionStore {
    private final DataSource dataSource;
    PostgresPipelineVersionStore(DataSource dataSource) { this.dataSource = dataSource; }
    public PipelineVersion publish(TenantId tenant, String source, PipelineVersion version) {
        Transactions.run(dataSource, connection -> {
            try (var statement = connection.prepareStatement("""
                INSERT INTO integration.pipeline_version (tenant_id, source_instance_id, pipeline_id, revision, digest, definition)
                VALUES (?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING
                """)) {
                statement.setQueryTimeout(5);
                statement.setString(1, tenant.value()); statement.setString(2, source);
                statement.setString(3, version.definition().id()); statement.setInt(4, version.definition().revision());
                statement.setString(5, version.digest()); statement.setString(6, PipelineJson.encode(version.definition()));
                statement.executeUpdate();
            }
        });
        var stored = find(tenant, source, version.definition().id(), version.definition().revision()).orElseThrow();
        if (!stored.digest().equals(version.digest())) throw new PipelineException(Code.VERSION_CONFLICT);
        return stored;
    }
    public Optional<PipelineVersion> find(TenantId tenant, String source, String id, int revision) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            SELECT definition::text, digest FROM integration.pipeline_version
            WHERE tenant_id = ? AND source_instance_id = ? AND pipeline_id = ? AND revision = ?
            """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, tenant.value()); statement.setString(2, source);
            statement.setString(3, id); statement.setInt(4, revision);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                try { return Optional.of(new PipelineVersion(PipelineJson.decode(rows.getString(1)), rows.getString(2))); }
                catch (IllegalArgumentException corrupt) { throw new IllegalStateException("Stored pipeline failed verification"); }
            }
        } catch (SQLException failed) { throw new IllegalStateException("Pipeline lookup unavailable"); }
    }
    public void pin(TenantId tenant, String source, UUID run, PipelineVersion.Ref ref) {
        Transactions.run(dataSource, connection -> {
            try (var statement = connection.prepareStatement("""
                INSERT INTO integration.sync_pipeline_pin (tenant_id, source_instance_id, sync_run_id, pipeline_id, revision, digest)
                SELECT ?, ?, ?, ?, ?, ? FROM integration.source_sync_run
                WHERE tenant_id = ? AND id = ? AND source_instance_id = ? AND object_type = 'host' AND status = 'RUNNING'
                ON CONFLICT DO NOTHING
                """)) {
                statement.setQueryTimeout(5);
                statement.setString(1, tenant.value()); statement.setString(2, source); statement.setObject(3, run);
                statement.setString(4, ref.id()); statement.setInt(5, ref.revision()); statement.setString(6, ref.digest());
                statement.setString(7, tenant.value()); statement.setObject(8, run); statement.setString(9, source);
                statement.executeUpdate();
            }
        });
        if (!pinned(tenant, source, run).filter(ref::equals).isPresent()) throw new PipelineException(Code.VERSION_CONFLICT);
    }
    public Optional<PipelineVersion.Ref> pinned(TenantId tenant, String source, UUID run) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            SELECT pipeline_id, revision, digest FROM integration.sync_pipeline_pin
            WHERE tenant_id = ? AND source_instance_id = ? AND sync_run_id = ?
            """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, tenant.value()); statement.setString(2, source); statement.setObject(3, run);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new PipelineVersion.Ref(rows.getString(1), rows.getInt(2), rows.getString(3))) : Optional.empty();
            }
        } catch (SQLException failed) { throw new IllegalStateException("Pipeline pin unavailable"); }
    }
}
