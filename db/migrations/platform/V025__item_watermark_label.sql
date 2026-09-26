-- Item scans now bound their walk by an itemid watermark as well. The constraint keeps the same name
-- so a later widening stays a single, reviewable change; existing labels stay valid.
ALTER TABLE integration.source_sync_run
    DROP CONSTRAINT source_sync_run_scan_consistency_check,
    ADD CONSTRAINT source_sync_run_scan_consistency_check
        CHECK (scan_consistency IN ('offset-scan-attempt','hostid-watermark-snapshot','itemid-watermark-snapshot'));
