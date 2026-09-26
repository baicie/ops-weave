package com.acme.opsweave.platform.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

final class SchemaMigrator {
    private final DataSource dataSource;

    SchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void apply(String classpathResource, String migrationId) {
        if (applied(migrationId)) {
            return;
        }
        String sql = read(classpathResource);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.createStatement()) {
                lock.execute("SET LOCAL lock_timeout = '10s'");
                lock.execute("SET LOCAL statement_timeout = '30s'");
                lock.execute("SELECT pg_advisory_xact_lock(hashtextextended('opsweave-platform-schema',0))");
            }
            // Another starter may have completed this migration while this connection waited.
            if (applied(connection, migrationId)) { connection.commit(); return; }
            for (String statement : statements(sql)) {
                try (Statement command = connection.createStatement()) {
                    command.execute(statement);
                }
            }
            try (var mark = connection.prepareStatement(
                "INSERT INTO integration.schema_migration (id) VALUES (?) ON CONFLICT (id) DO NOTHING"
            )) {
                mark.setString(1, migrationId);
                mark.executeUpdate();
            }
            connection.commit(); // DDL and migration marker succeed or roll back together.
        } catch (SQLException failed) {
            throw new IllegalStateException("Inventory schema migration failed");
        }
    }
    private boolean applied(Connection connection, String migrationId) throws SQLException {
        try (var catalog=connection.createStatement();var rows=catalog.executeQuery("SELECT to_regclass('integration.schema_migration')")) { if(!rows.next()||rows.getString(1)==null)return false; }
        try(var s=connection.prepareStatement("SELECT 1 FROM integration.schema_migration WHERE id=?")){s.setString(1,migrationId);try(var rows=s.executeQuery()){return rows.next();}}
    }

    private boolean applied(String migrationId) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                 "SELECT 1 FROM integration.schema_migration WHERE id = ?"
             )) {
            statement.setString(1, migrationId);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException missing) {
            return false;
        }
    }

    private static String read(String classpathResource) {
        try (var input = SchemaMigrator.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (input == null) {
                throw new IllegalStateException("Missing inventory migration");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new IllegalStateException("Missing inventory migration");
        }
    }

    static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String statement = current.toString().trim();
                if (statement.endsWith(";")) {
                    statement = statement.substring(0, statement.length() - 1).trim();
                }
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            }
        }
        return List.copyOf(statements);
    }
}
