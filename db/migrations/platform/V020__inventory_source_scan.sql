-- One durable fence per source scope; expiry never authorizes an old token to write again.
CREATE TABLE inventory.source_scan_lease (
    tenant_id varchar(128) NOT NULL,
    source_instance_id varchar(128) NOT NULL,
    external_type varchar(64) NOT NULL,
    run_id uuid NOT NULL,
    fence bigint NOT NULL CHECK(fence BETWEEN 1 AND 9007199254740991),
    started_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    lease_until timestamptz NOT NULL,
    released boolean NOT NULL,
    PRIMARY KEY(tenant_id,source_instance_id,external_type),
    CHECK(deadline_at=started_at+interval '5 minutes' AND lease_until>started_at AND lease_until<=deadline_at)
);
