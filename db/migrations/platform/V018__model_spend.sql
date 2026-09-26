-- Durable estimated spend admission. Unknown outcomes retain their reservations across UTC days.
CREATE TABLE IF NOT EXISTS ai_control.model_spend (
    tenant_id varchar(128) NOT NULL,
    run_id uuid NOT NULL,
    day date NOT NULL,
    reported boolean NOT NULL,
    accounted_micros bigint NOT NULL CHECK (accounted_micros >= 0),
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 16384),
    PRIMARY KEY (tenant_id, run_id)
);
CREATE INDEX IF NOT EXISTS model_spend_budget ON ai_control.model_spend(tenant_id,day,reported);
