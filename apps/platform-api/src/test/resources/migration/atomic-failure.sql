-- Deliberate test failure after DDL: no real business table is touched.
CREATE TABLE integration.test_incident_migration_rollback_probe (id integer PRIMARY KEY);
SELECT opsweave_deliberately_missing_migration_test_function();
