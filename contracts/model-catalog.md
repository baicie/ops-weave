# Model catalog v1

Canonical schemas live in schemas/v1/model-*.schema.json; the shipped package is catalog/opsweave-core-1.0.0.json. Java/TypeScript adapters implement these contracts. The package contains definitions, never live assets, relationship instances or metric samples.

## HTTP and authority

All paths use /api/v1/catalog. Authentication constructs the Principal. Request tenant/subject/permission fields are rejected. Read and sample-preview require entity.read on the catalog:* resource; save/publish require entity.manage on catalog:*. A trusted tenant-wide scope includes this resource. entity:* alone does not grant catalog administration. Tenant is never taken from a model id or payload.

- GET /: shipped package, at most 50 tenant published versions and 50 current-subject drafts, each with an explicit truncated flag; storage is postgres or explicitly configured memory. This is a bounded first catalog, not an unbounded search interface.
- GET /versions/{id}/{revision}: one immutable custom version within the trusted tenant.
- POST /drafts: {definition, expectedEditVersion}. Zero creates; later writes compare the private draft edit version. Conflicts return 409. A draft key is tenant + subject + type id + target revision.
- POST /publish: {ref:{id,revision}, expectedEditVersion, digest}. Publishes exactly the saved private draft. No client definition is accepted here. A retry with the same pinned draft returns the original publication; different content for an existing type version returns 409.
- POST /preview: {definition, sample}. One bounded scalar record, no asset writes, no persistence, no source/model/network calls. A model definition may be previewed without publishing; schema publication itself does not claim a data-quality acceptance or execute a pipeline.

Each body is limited to 64 KiB; the canonical encoded definition is limited to 16 KiB to keep both bounded pages below the shared 2 MiB response budget. duplicate JSON keys/trailing documents/unknown properties are rejected. SQL statements have a five-second timeout. Model ids are builtin.* or custom.*; only custom.* can be written. Definitions have at most 32 fields, revision 1–10000; published entity and relationship kinds cannot be exchanged. Entity relation endpoints name already published ENTITY definitions in this tenant, or the fixed built-in package. Definition references pin id and revision, and immutable entries carry a SHA-256 digest. No external schema references or executable expressions are accepted.

Private drafts use optimistic editVersion. Publication and endpoint/previous-version checks run in one PostgreSQL transaction under a tenant catalog advisory lock. Readers verify the stored definition, key and digest. V028 introduces catalog.model_draft/model_version; runtime grants permit update only on drafts, never published versions. In-memory mode is explicit and loses data on restart; PG failure never falls back to it.

## Definition and compatibility

Five built-in entities: host, application, service, database, network_interface. Four built-in relation types: contains, deployed_on, depends_on, has_interface. Three metric definitions reuse existing mapping keys; metric stream bindings and actual sampling remain separate.

Custom scalar fields: TEXT, INTEGER, DECIMAL, BOOLEAN, ENUM, DATETIME. Stable lowercase ids cannot occupy reserved system metadata names. TEXT/ENUM require maxLength (1–2048); ENUM requires 1–32 unique choices. Numeric bounds are only valid for numeric fields, min <= max, within JavaScript's exact integer range; integer bounds must be integral. Fields are unique. Domain validation supplements JSON Schema where cross-field comparisons are needed.

Until an explicit instance migration workflow is implemented, revisions are consecutive and additive: existing field ids, scalar types, requiredness, length/range/enum semantics and relationship endpoints/cardinality remain fixed. Labels/descriptions may change; new fields must be optional. Removal, required-field additions, skipped versions and endpoint changes return INCOMPATIBLE_REVISION. Old records are never migrated or relinked during publication.

Digest algorithm opsweave-model-v1 hashes UTF-8 components prefixed with their byte length and a colon. It includes type identity/revision/kind/label/description/profile, fields sorted by stable id (including choices in order), and relation endpoints/cardinality. Decimal bounds use canonical plain notation. Database JSON property order does not affect integrity.

## Tenant extension overlays

Tenant-namespaced extension overlays add optional bounded scalar fields to one fixed base model version. The base model body is immutable: an overlay never edits built-in or published definitions, never shadows base field identities or reserved system metadata and never becomes a new model identity. Only additive optional fields are allowed; combined base + overlay fields stay within the 32-field budget.

- GET /api/v1/catalog/extensions: at most 50 published overlay versions and 50 current-subject drafts with explicit truncated flags. Overlay versions are selected by base {id, revision}; the request never accepts a tenant or actor.
- POST /api/v1/catalog/extensions/drafts: {base:{id,revision}, fields, expectedEditVersion}. Zero creates a private draft; the overlay revision is server-assigned as latest published + 1. The base must be a built-in definition or an already published ENTITY version inside the trusted tenant. Illegal field names/types, duplicate field identities, reserved system names, base field collisions and budget overflow are rejected server-side; a request without fields is rejected. Conflicts return 409.
- POST /api/v1/catalog/extensions/publish: {base, expectedEditVersion, digest}. Publishes exactly the saved draft as the next immutable extension revision under the same tenant advisory lock; revisions are consecutive and additive with earlier field semantics pinned, so records written against an earlier revision stay readable. A retry with the same pinned draft returns the original publication.

Digest algorithm opsweave-model-extension-v1 hashes the same length-prefixed UTF-8 components over the base reference, overlay revision, profile and extension fields sorted by stable id. Entity instance writes validate attributes against the base body plus the tenant's published overlay for that pinned base version; extension values persist inside the ordinary attributes payload and are read back with their field ids. When no definition is available the presentation keeps raw field ids instead of guessing labels. Rejections distinguish invalid content (400), missing base/draft (404) and edit-version/digest/compatibility conflicts (409).

## Legacy entity model pin backfill

- POST /api/v1/entities/model-pins/backfill: {requestId, entityType, model:{id,revision}, maxUpdates}. An explicit, bounded, authorized maintenance command for legacy rows that predate model pinning. The server resolves the named published model version, derives its entity type and digest, and pins only rows whose stored attributes verifiably conform to exactly that model. Rows that cannot be determined keep no pin and are reported as counts instead of being guessed; source-managed rows (those with source observations) and rows that already carry a pin are never touched. At most maxUpdates (1–500) unpinned non-source rows are scanned per command in deterministic entity-id order; nothing is rewritten implicitly outside the command. requestId is the idempotency key: retries replay the stored receipt and the same requestId with changed content returns 409. The receipt reports counts only (scanned/updated/keptWithoutPin/skippedPinned/skippedSource) and never entity contents. Tenant-wide entity.manage is required; the request never accepts a tenant or actor.
- GET /api/v1/entities/model-pins/backfill/{requestId}: reads the stored receipt of one command by its own requestId. An unknown requestId returns 404 instead of inventing a result.

## safe-scalars-v1

Rules are fixed and visible in this release; users can preview them, but cannot supply custom scripts or overwrite a published rule version.

1. TEXT/ENUM strip surrounding whitespace; non-text inputs are not stringified.
2. Numeric strings accept plain decimal syntax, no leading zeroes, exponent, hex, locale commas or NaN. JSON numeric values are checked numerically. INTEGER rejects fractional values; numbers are bounded to ±9007199254740991 and at most 12 fractional digits. BOOLEAN accepts a JSON boolean or trimmed lowercase true/false, not 0/1. DATETIME requires an explicit offset and normalizes to UTC; no local timezone is guessed.
3. Missing required fields, required null and empty required text have distinct issue codes. Optional missing fields remain absent; optional null remains null. No invented default values.
4. Validate length, enum and numeric bounds. Unknown fields are reported rather than silently dropped as a successful record.
5. Result valid=false means values is only the successfully converted subset, not a writable entity. The UI retains the input sample and shows field-level issues. Samples are never stored or logged by this API.

Metric percent→ratio and unit semantics remain in the existing versioned metric mappings; the scalar model preview does not pretend to execute those source pipelines.

## Source versions

Zabbix apiinfo.version already supports read-only discovery. Vendor-specific authentication/API/field normalization belongs in the connector before the shared cleaning/model layer. The package records the previously locally exercised version 7.0.27; all other versions are UNVERIFIED. This metadata is not a new executable compatibility gate or proof of a current connection. Per-version capability profiles and integration fixtures must be added with the source-center stage; no other Zabbix version was tested in this slice.

The distinction matters: [Zabbix 7.2 API changes](https://www.zabbix.com/documentation/7.2/en/manual/api/changes) remove the deprecated JSON-RPC auth property. Our existing transport uses Bearer headers; this alone does not establish whole-API compatibility. [apiinfo.version](https://www.zabbix.com/documentation/7.0/en/manual/api/reference/apiinfo/version) is the version-discovery API.

## Explicit remaining work

This slice provides model definitions, sample validation, tenant extension overlays and the explicit legacy model-pin backfill. Custom metric authoring, custom transformation rules, source configuration drawers, pipeline v2/canvas and relationship instance/history/topology management are not implemented here. Legacy Host v1 ingestion remains unchanged and legacy rows keep their state until an explicit backfill command matches them. AI assistance stays reserved for later, with no new Tool permissions or model calls.
