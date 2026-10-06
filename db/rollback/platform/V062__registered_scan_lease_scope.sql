ALTER TABLE inventory.source_scan_lease
    DROP CONSTRAINT IF EXISTS source_scan_lease_scope_digest_check,
    DROP COLUMN IF EXISTS scope_digest;
