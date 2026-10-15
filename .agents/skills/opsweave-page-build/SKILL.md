---
name: opsweave-page-build
description: Implement or refactor OpsWeave 0.1.0 React and TypeScript pages with approved UI blueprint, semantic components, real API contracts, all async states, accessible interaction and test evidence.
---

# OpsWeave Page Implementation

## Trigger
Use for frontend JSX/CSS work on OpsWeave 0.1.0, or when converting an agreed page blueprint into code.

## Before editing
- Read root `AGENTS.md`, `docs/ui/visual-system.md`, `docs/ui/interaction-contract.md`, `docs/ui/component-contracts.md`, `docs/ui/page-blueprints.md`.
- Inspect current files & generated API contracts; do not guess endpoints. Old web-console is for comparison and compatibility, not a page template.
- Require a page blueprint; if absent, perform `opsweave-ui-design` steps briefly.

## Implementation order
1. Build semantic page skeleton + global shell slots. One primary CTA; choose proper content width / inspector ownership.
2. Implement API adapter and typed view models separate from rendering. No permission, provenance or RCA calculations in JSX.
3. Extract coherent components (list, header, toolbar, evidence row, hypothesis details); avoid one giant hook/component.
4. Implement state matrix: initial/loading/ready/empty/partial/stale/error/forbidden/fixture; preserve UNKNOWN semantics. Cancellation/authorization change must clear sensitive state.
5. Use Design Tokens for both themes; avoid inline colors and per-page button styles. Include keyboard focus, labels, reduced motion and accessible text for non-color status.
6. Add typecheck/build + affected Playwright tests; inspect real screenshots for 1440/1024/390 and light/dark, run `opsweave-visual-audit` steps. Fix P0/P1 defects before claiming completion.

## Output
- File list, component boundaries, API contract references, screenshots (if actually taken), checks actually run, failure/unknown states verified, remaining risks.
- If backend is unavailable, use an explicitly labeled **Fixture preview**, NEVER imply live customer data.

## Invariants
- Respect existing four startup units and Java authorization boundary.
- No top-level WorkflowStore additions, no unsanctioned API fields, no hidden retry of UNKNOWN operations.
- Nothing automatically launches a production mutation from UI preview or mock.
