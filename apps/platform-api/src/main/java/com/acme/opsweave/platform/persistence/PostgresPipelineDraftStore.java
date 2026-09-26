package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.identity.domain.SubjectId;
import com.acme.opsweave.integration.api.PipelineDraftStore;
import com.acme.opsweave.integration.domain.*;
import com.acme.opsweave.platform.integration.PipelineJson;
import com.acme.opsweave.sharedkernel.TenantId;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

final class PostgresPipelineDraftStore implements PipelineDraftStore {
    private final DataSource dataSource;
    PostgresPipelineDraftStore(DataSource dataSource) { this.dataSource = dataSource; }
    public PipelineDraft save(TenantId tenant, String source, SubjectId owner, PipelineVersion content, int expected, Instant now) {
        var next = PipelineDraft.save(content, expected, now);
        String sql = expected == 0 ? """
            INSERT INTO integration.pipeline_draft (definition, digest, edit_version, updated_at, tenant_id, source_instance_id, owner_subject, pipeline_id, revision)
            VALUES (?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING *
            """ : """
            UPDATE integration.pipeline_draft SET definition = ?::jsonb, digest = ?, edit_version = ?, updated_at = ?
            WHERE tenant_id = ? AND source_instance_id = ? AND owner_subject = ? AND pipeline_id = ? AND revision = ? AND edit_version = ? RETURNING *
            """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5); statement.setString(1, PipelineJson.encode(content.definition())); statement.setString(2, content.digest());
            statement.setInt(3, next.editVersion()); statement.setTimestamp(4, Timestamp.from(now));
            statement.setString(5, tenant.value()); statement.setString(6, source); statement.setString(7, owner.value());
            statement.setString(8, content.definition().id()); statement.setInt(9, content.definition().revision());
            if (expected != 0) statement.setInt(10, expected);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new PipelineException(PipelineException.Code.DRAFT_CONFLICT);
                return draft(rows);
            }
        } catch (SQLException failed) { throw new IllegalStateException("Draft save unavailable"); }
    }
    public Optional<PipelineDraft> find(TenantId tenant, String source, SubjectId owner, String id, int revision) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            SELECT * FROM integration.pipeline_draft
            WHERE tenant_id = ? AND source_instance_id = ? AND owner_subject = ? AND pipeline_id = ? AND revision = ?
            """)) {
            statement.setQueryTimeout(5); scope(statement, tenant, source, owner); statement.setString(4, id); statement.setInt(5, revision);
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(draft(rows)) : Optional.empty(); }
        } catch (SQLException failed) { throw new IllegalStateException("Draft lookup unavailable"); }
    }
    public List<PipelineDraft.Header> list(TenantId tenant, String source, SubjectId owner, int limit) {
        if (limit < 1 || limit > 51) throw new IllegalArgumentException("Invalid draft limit");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
            SELECT pipeline_id, revision, digest, edit_version, updated_at FROM integration.pipeline_draft
            WHERE tenant_id = ? AND source_instance_id = ? AND owner_subject = ?
            ORDER BY updated_at DESC, pipeline_id DESC, revision DESC LIMIT ?
            """)) {
            statement.setQueryTimeout(5); scope(statement, tenant, source, owner); statement.setInt(4, limit);
            var result = new ArrayList<PipelineDraft.Header>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(new PipelineDraft.Header(new PipelineVersion.Ref(rows.getString("pipeline_id"), rows.getInt("revision"), rows.getString("digest")),
                    rows.getInt("edit_version"), rows.getTimestamp("updated_at").toInstant()));
            }
            return List.copyOf(result);
        } catch (SQLException failed) { throw new IllegalStateException("Draft list unavailable"); }
    }
    private static PipelineDraft draft(ResultSet rows) throws SQLException {
        try {
            var content = new PipelineVersion(PipelineJson.decode(rows.getString("definition")), rows.getString("digest"));
            if (!content.definition().id().equals(rows.getString("pipeline_id")) || content.definition().revision() != rows.getInt("revision"))
                throw new IllegalArgumentException("Draft scope mismatch");
            return new PipelineDraft(content, rows.getInt("edit_version"), rows.getTimestamp("updated_at").toInstant());
        } catch (RuntimeException corrupt) { throw new IllegalStateException("Stored draft failed verification"); }
    }
    private static void scope(PreparedStatement statement, TenantId tenant, String source, SubjectId owner) throws SQLException {
        statement.setString(1, tenant.value()); statement.setString(2, source); statement.setString(3, owner.value());
    }
}
