-- EXAMPLE ONLY; not an all-table security implementation.
-- Execute DDL with a migration role. Runtime role must be non-owner, NOSUPERUSER, NOBYPASSRLS.
ALTER TABLE inventory.entity ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.entity FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.entity
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
-- For each authorized request transaction:
-- BEGIN;
-- SELECT set_config('app.tenant_id', <trusted-tenant-value>, true);
-- Execute parameterized SQL as the restricted application role.
-- COMMIT;
-- Apply and test policies for ALL tenant-scoped tables. Never use session-global SET with pooled connections.
-- This setting is not authentication. Prevent arbitrary SQL and enforce trusted identity before binding it.
