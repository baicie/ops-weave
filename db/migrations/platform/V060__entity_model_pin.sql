ALTER TABLE inventory.entity
    ADD COLUMN model_id varchar(64),
    ADD COLUMN model_revision integer,
    ADD COLUMN model_digest varchar(71),
    ADD CONSTRAINT entity_model_pin_complete CHECK (
        (model_id IS NULL AND model_revision IS NULL AND model_digest IS NULL)
        OR (model_id IS NOT NULL AND model_revision IS NOT NULL AND model_digest IS NOT NULL
            AND model_id ~ '^(builtin|custom)\.[a-z][a-z0-9_]{0,47}$'
            AND model_revision BETWEEN 1 AND 10000
            AND model_digest ~ '^sha256:[a-f0-9]{64}$')
    );
