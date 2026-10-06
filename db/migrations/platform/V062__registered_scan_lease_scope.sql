ALTER TABLE inventory.source_scan_lease
    ADD COLUMN scope_digest varchar(71),
    ADD CONSTRAINT source_scan_lease_scope_digest_check CHECK (
        scope_digest IS NULL OR scope_digest ~ '^sha256:[a-f0-9]{64}$'
    );

COMMENT ON COLUMN inventory.source_scan_lease.scope_digest IS
    'Immutable registered connection scope for the current fence; NULL is legacy/unregistered.';
