-- Records how a stored scan bounded its walk, so a trace can tell a verified snapshot from an offset
-- attempt. Existing rows keep the offset label: they were written before the watermark walk existed.
ALTER TABLE integration.source_sync_run
    ADD COLUMN scan_consistency varchar(64) NOT NULL DEFAULT 'offset-scan-attempt'
    CHECK (scan_consistency IN ('offset-scan-attempt','hostid-watermark-snapshot'));
