-- Apply as the schema owner AFTER platform V002..V043 and ingestion V001..V002 migrations.
-- Supply psql identifier variables platform_role and history_role (distinct, newly provisioned
-- LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS roles).
-- This file adds only explicit privileges. It does not revoke PUBLIC or existing role grants;
-- operators must audit inherited/PUBLIC rights and ownership separately. Never grant these roles
-- membership in the migration-owner role. No passwords or CREATE ROLE statements belong here.
-- No future-table/default privileges: review this allowlist when a migration adds a table.
\set ON_ERROR_STOP on
-- Reject shared, elevated, inherited, or object-owning identities before applying any grant.
SELECT 1 / ((count(*) = 2)::integer) AS runtime_role_preconditions
FROM pg_roles r
WHERE r.rolname IN (:'platform_role', :'history_role')
  AND r.rolcanlogin AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolinherit OR r.rolreplication OR r.rolbypassrls)
  AND NOT EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.member = r.oid)
  AND NOT EXISTS (SELECT 1 FROM pg_database d WHERE d.datdba = r.oid)
  AND NOT EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspowner = r.oid)
  AND NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.relowner = r.oid);
BEGIN;
GRANT USAGE ON SCHEMA inventory, integration, telemetry, alerting, incident, ai_control, audit, catalog TO :"platform_role";
GRANT SELECT, INSERT ON integration.source_setup TO :"platform_role";
GRANT SELECT, INSERT, UPDATE ON integration.source_instance TO :"platform_role";
GRANT SELECT, INSERT ON integration.source_configuration, integration.source_command TO :"platform_role";
GRANT SELECT, INSERT ON integration.source_connection_configuration TO :"platform_role";
GRANT SELECT, INSERT, UPDATE ON integration.source_inspection TO :"platform_role";
GRANT SELECT, INSERT, UPDATE ON integration.source_credential TO :"platform_role";
GRANT SELECT, INSERT ON integration.source_credential_version, integration.source_credential_revocation, integration.source_credential_command TO :"platform_role";
GRANT SELECT, INSERT ON integration.workflow_version, integration.workflow_run TO :"platform_role";
GRANT SELECT, INSERT ON integration.workflow_execution TO :"platform_role";
GRANT SELECT, INSERT, UPDATE ON integration.workflow_task TO :"platform_role";
GRANT SELECT, INSERT ON integration.workflow_control_command TO :"platform_role";
GRANT SELECT, INSERT, UPDATE ON integration.workflow_metric_output TO :"platform_role";
GRANT SELECT, INSERT, UPDATE ON integration.workflow_draft TO :"platform_role";
GRANT SELECT, INSERT ON catalog.model_version TO :"platform_role";
GRANT SELECT, INSERT, UPDATE ON catalog.model_draft TO :"platform_role";
GRANT SELECT ON inventory.entity_relation TO :"platform_role";
GRANT SELECT ON integration.schema_migration TO :"platform_role";

GRANT SELECT, INSERT ON
    inventory.entity_external_link, inventory.entity_observation,
    inventory.source_review_receipt, inventory.asset_identity_receipt,
    inventory.source_snapshot, inventory.source_binding_correction,
    integration.raw_record_metadata, integration.pipeline_version, integration.sync_pipeline_pin,
    alerting.problem_observation, incident.transition_request, incident.reorganization_request,
    ai_control.ai_insight_evidence, ai_control.retention_receipt
TO :"platform_role";

GRANT SELECT, INSERT, UPDATE ON
    inventory.entity, inventory.source_review, inventory.entity_source_authority,
    inventory.asset_identity, inventory.source_scan_lease,
    integration.pipeline_replay_run, integration.pipeline_draft,
    telemetry.metric_definition, telemetry.metric_binding,
    alerting.external_problem, incident.incident,
    ai_control.tool_read_session, ai_control.evidence_snapshot, ai_control.ai_insight, ai_control.model_spend
TO :"platform_role";

GRANT SELECT, INSERT, UPDATE, DELETE ON
    inventory.entity_source_presence, integration.source_sync_run, audit.tool_read_call
TO :"platform_role";
GRANT SELECT, INSERT, DELETE ON
    integration.source_connection_check, integration.rejected_write_attempt
TO :"platform_role";

GRANT USAGE ON SCHEMA ingestion TO :"history_role";
GRANT SELECT, INSERT, UPDATE ON ingestion.history_checkpoint TO :"history_role";
COMMIT;

GRANT SELECT, INSERT, UPDATE ON integration.workflow_host_checkpoint, integration.workflow_host_batch TO :"platform_role";
