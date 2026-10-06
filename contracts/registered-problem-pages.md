# Registered connection problem pages

`GET /api/v2/data-sources/{sourceId}/connection/{revision}/problems` reads one bounded page of source problems through a maintained connection revision. The path is the complete binding: the server resolves the endpoint, credential version, tenant and host-group scope from the registered revision. Query input cannot replace any of those values.

The required `from` and `till` parameters are inclusive UTC epoch seconds. `afterEventId` is an opaque source event cursor and `limit` is 1-100 (default 25). The response echoes the fixed connection digest, host-group IDs and a scope digest so a caller can detect that a page belongs to a different revision. A non-empty host-group scope is required; legacy unscoped revisions are not accepted by this endpoint.

The page is read-only. It does not create incidents, resolve existing problems, send notifications or run workflow actions. Source failures, invalid source responses and unavailable credentials remain errors; an empty page is not treated as a complete inventory snapshot. `nextAfterEventId` is present only when the source returned a full page.

The public JSON shape is maintained in `schemas/v2/registered-problem-page.schema.json` and `schemas/v2/registered-problem.schema.json`.
