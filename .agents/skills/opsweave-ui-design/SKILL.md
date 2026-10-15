---
name: opsweave-ui-design
description: Design OpsWeave 0.1.0 Web Console screens before coding. Use for information architecture, visual hierarchy, task-first page layouts, UX design and new page proposals. Outputs an implementable UI blueprint without generating unrelated feature scope.
---

# OpsWeave UI Design

## Trigger
Use when user asks to design/redesign UI, page layout, navigation, onboarding, visual hierarchy or improve product appearance.

## Read first
- Repo `AGENTS.md` + `docs/ui/visual-system.md` + `docs/ui/page-blueprints.md` + `docs/ui/interaction-contract.md`.
- Read the current architecture and frontend guidance in `docs/architecture/v4-design.md`, `docs/development/frontend-guidelines.md` and `docs/FRONTEND-COMPATIBILITY.md` when the page touches those boundaries; `docs/ui` is the immediate UI source.
- Inspect the actual user story and existing API adapters. Treat screens as **operator workflows**, not CRUD forms.

## Workflow (MUST follow order)
1. **Task:** 1 sentence: who, what decision, what proof of success. If no core task, pause scope expansion; infer from 0.1.0 Golden Path.
2. **Information inventory:** list important data as `primary / supporting / advanced`; remove redundant values.
3. **Layout:** create ASCII wireframe for desktop plus 1024 and 390 fallbacks. Declare sidebar/header/inspector widths and scroll owners.
4. **Interaction:** list every intent (click, select, filter, drill-down), loading/error/partial/stale/forbidden/empty/fixture states and navigation return path.
5. **Visual:** choose typography hierarchy, tokens, component structure and only one primary CTA; justify any exception.
6. **Review before coding:** perform self-critique: card nesting, excessive chrome, visual noise, dense typography, broken time/units, inaccessible status.

## Required output
- `Screen purpose`, `User story`, `Priority hierarchy`, `Desktop/Narrow/Mobile wireframes`, `Components`, `Async states`, `Interaction map`, `Acceptance tests`, `Open questions (only blocking)`.
- Never claim design is visually validated without real screenshot/browser inspection.

## Prohibited
- Recreating the old 20+ route menu or copying the large legacy Workflow page into the new UI.
- Styling-before-structure; random colors/gradients, decorative cards; 'UI like a well-known brand' in repository files.
- Inventing telemetry values; labels, status and causality must come from approved contracts.
