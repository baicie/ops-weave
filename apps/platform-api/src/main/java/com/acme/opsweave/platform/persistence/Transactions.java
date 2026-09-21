package com.acme.opsweave.platform.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

final class Transactions {
    private Transactions() {}

    static void run(DataSource dataSource, SqlWork work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.accept(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failed) {
                connection.rollback();
                throw failed instanceof SQLException ? new IllegalStateException("Inventory database write failed") : (RuntimeException) failed;
            }
        } catch (SQLException failed) {
            throw new IllegalStateException("Inventory database write failed");
        }
    }

    @FunctionalInterface
    interface SqlWork {
        void accept(Connection connection) throws SQLException;
    }
}
