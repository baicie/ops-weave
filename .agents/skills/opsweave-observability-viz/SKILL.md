---
name: opsweave-observability-viz
description: Design accurate OpsWeave observability screens for Metrics, Logs, Traces, topology, evidence timelines and investigation comparisons. Use when creating charts, trace waterfall, time windows or RCA evidence visualizations.
---

# OpsWeave Observability Visualization

## Trigger
For any Metric/Trace/Log chart, timeline, incident evidence list, resource topology or RCA visualization.

## Read first
`docs/ui/observability-visualization.md`, `docs/ui/interaction-contract.md` and `docs/ui/visual-system.md`.

## Contract-first process
1. Enumerate source signals, actual units, aggregation, sample spacing, time zone, query window, missing intervals, source and dataMode.
2. Choose chart by operator decision: trend=line, distribution=histogram, breakdown=table/bar, trace=waterfall, event=timeline; no chart for a single meaningless sample.
3. Align cross-source timestamps **without** silently interpolating missing data; name resolution, gaps and sampling limitations.
4. Make Evidence selection and context navigation reversible; evidence IDs link to source query/record where permitted.
5. Render `NO_DATA`, `PARTIAL`, `STALE`, `UNAVAILABLE`, `FORBIDDEN`, `FIXTURE`, `UNKNOWN` differently and textually; cannot infer healthy status from absence.
6. Compare Support / Contradiction / Untested hypotheses without made-up confidence %. Keep observations and causal inferences separate.
7. Create Playwright screenshot checks with Representative Fixture clearly labeled and real-data test evidence separately.

## Required output
Data contract mapping, chart specification, interaction map, state matrix, test fixtures, screenshot assessment and unresolved semantic questions.

## Avoid
- Dual axis without explicit labeling; area fill suggesting exact causal magnitude; aggregated values marked instantaneous; p95 compared directly to average without clear distinction.
- 0 substituted for missing intervals; timezone drift between charts; secrets or raw sensitive log content in screenshots.
