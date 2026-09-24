-- Move an existing V001 table to lease/fencing and one checkpoint per series.
-- Safe to re-run. Refuses to collapse two stream rows for the same item.
ALTER TABLE ingestion.history_checkpoint
    ADD COLUMN IF NOT EXISTS fencing_token bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS lease_until timestamptz;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'ingestion.history_checkpoint'::regclass
          AND conname = 'history_checkpoint_fencing_token_check'
    ) THEN
        ALTER TABLE ingestion.history_checkpoint
            ADD CONSTRAINT history_checkpoint_fencing_token_check CHECK (fencing_token >= 0);
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'ingestion.history_checkpoint'::regclass
          AND conname = 'history_checkpoint_pkey'
          AND pg_get_constraintdef(oid) LIKE '%stream_name%'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM ingestion.history_checkpoint
            GROUP BY tenant_id, source_instance_id, item_id
            HAVING count(*) > 1
        ) THEN
            RAISE EXCEPTION 'duplicate history checkpoints for one tenant/source/item';
        END IF;
        ALTER TABLE ingestion.history_checkpoint DROP CONSTRAINT history_checkpoint_pkey;
        ALTER TABLE ingestion.history_checkpoint
            ADD PRIMARY KEY (tenant_id, source_instance_id, item_id);
    END IF;
END $$;
