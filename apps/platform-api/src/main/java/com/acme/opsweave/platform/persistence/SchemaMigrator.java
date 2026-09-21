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
        String sql = read(classpathResource);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
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
        } catch (SQLException failed) {
            throw new IllegalStateException("Inventory schema migration failed");
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
