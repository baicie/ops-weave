---
name: opsweave-visual-audit
description: Audit OpsWeave UI quality with real browser screenshots in multiple viewports and themes. Use after implementing screens, on appearance complaints, layout bugs, screenshot reviews and pre-PR UI quality gates.
---

# OpsWeave Visual Audit

## Trigger
After UI coding, before marking a visual task complete, or whenever the user says UI feels ugly, crowded, inconsistent or hard to use.

## Prerequisite
Read `docs/ui/review-scorecard.md`, `docs/ui/visual-system.md`, `docs/ui/interaction-contract.md`. Locate actual runnable page; honor login/permissions. Never fabricate screenshots.

## Workflow
1. **Capture evidence**: use real browser/Playwright; target `1440×900`, `1024×768`, `390×844`, each in light and dark. For complex charts, optional `1920×1080`; record browser, commit, theme, URL, dataMode and loading completion. If unavailable mark `NOT_RUN`.
2. **Inspect individually**: verify hierarchy, main CTA, whitespace, table density, cards, line lengths, alignment, typography, contrast, chart axes/units, scroll boundaries, dialogs, sticky areas, focus and keyboard interactions.
3. **State coverage**: simulate Loading, Empty, Error, Partial, Forbidden, Stale and Fixture. Do not fake Real data. Check misleading charts, Evidence/Fact/Hypothesis distinction, stale badges.
4. **Categorize**: P0 misleading data, secrets/forbidden exposure or inaccessible primary action; P1 overlap/overflow/low contrast/unreachable detail/missing states; P2 polish.
5. **Fix and recapture**: make targeted changes, re-run tests, take post-fix screenshots, compare the same route+viewport+state.
6. **Report**: follow scorecard; include scores, remaining findings, file paths and only test commands actually executed.

## Exit gate
- No unresolved P0/P1 on mandatory viewports, score >= 85/100, all required states visible and no factual data mislabeling.
- A score does NOT override critical failures; no screenshot/no browser => cannot pass visual gate.

## Failure modes to reject
- 'Looks great' based on reading JSX only.
- Fixing light desktop only while narrow/dark is broken.
- Adding more Card/shadows/colors to hide a poor hierarchy.
- Treating fixture samples as measured production telemetry.
