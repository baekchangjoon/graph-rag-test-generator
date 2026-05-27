# coverage-feedback

Stages 5+6 of the 6-stage pipeline. Parses a JaCoCo XML report, diffs it against
the previous iteration's still-missing branches, and decides whether the
orchestrator should iterate again or stop. T6 wires this into a loop; this
module is the loop's brain.

## CLI

```bash
./gradlew :coverage-feedback:installDist

./coverage-feedback/build/install/coverage-feedback/bin/coverage-feedback \
  --jacoco-xml          /path/to/jacoco.xml \
  --coverage-target     0.85 \
  --out                 /tmp/iter-3 \
  [--previous-deltas-dir /tmp]                # contains iter-1/, iter-2/ with coverage-delta.json
```

Outputs (under `--out`):

| file | content |
|---|---|
| `coverage-delta.json` | `branch_coverage_total` + `line_coverage_total` + `newly_covered[]` + `still_missing[]` |
| `termination-decision.json` | `should_terminate` + `reason` (`target_reached` / `two_iterations_no_progress`) + `target_reached` |
| `next-iteration-hints.json` | `focus_branches[]` + `exclude_paths[]` + `boundary_value_hints` — **omitted** when terminating |

Exit codes: 0 ok, 2 bad flags, 4 i/o or parse error.

## Termination rules (R6 mitigation)

The orchestrator stops the loop the first moment any of these are true:

1. **`branch_coverage_total ≥ coverage-target`** — happy case, target reached.
2. **The last two iterations both had empty `newly_covered`** — the Stage 1
   analyzer keeps re-finding the same paths and they don't reach more branches,
   so further iterations would burn budget without progress.

If neither holds, the orchestrator feeds `next-iteration-hints.json` back into
the next Stage 1 invocation (`path-discovery-static --exclude-paths …`).

## What `newly_covered` actually means

`newly_covered = previous_iteration.still_missing − current_iteration.still_missing`.

This deliberately compares to the **previous still-missing set**, not the
previous totals. Why: Stage 1 can find brand-new endpoints between iterations
(e.g. after `--exclude-paths` shifts focus), which would inflate the branch
count and pollute a naïve "covered grew by N" delta. The set-difference is
robust to count changes.

## Security note

The JaCoCo XML parser is OWASP-hardened against XXE (DOCTYPE disabled, external
entity resolution off). A test (`rejects_xml_with_doctype_xxe_payload`) locks
that down — JaCoCo reports are typically generated locally but the parser still
treats them as untrusted input by default.

## Risk mitigation

| Risk | How |
|---|---|
| **R6** (no convergence) | The `two_iterations_no_progress` rule + the orchestrator's `--max-iterations` cap. |
| **R8** (pipeline complexity) | Every output is a structured JSON file with a record schema, so each iteration can be replayed independently from disk. |
