---
name: result-verification
description: Verify a claimed result against observable evidence and explicit completion criteria.
license: Apache-2.0
metadata:
  haifa.version: 1.3.0
---

# Result verification

Use this Skill before claiming that a non-trivial task is complete.

1. Re-read the original request and authoritative repository contracts; do not verify only against a plan, summary,
   TODO, or memory.
2. Enumerate the complete contract proportionately: behavior, public API/data, errors,
   state/side effects/order, boundaries, compatibility, mutation scope, and non-functional constraints.
3. Map each item to final implementation/evidence or mark it inferred, missing, conflicting, or blocked. Core logic or
   a happy path cannot cover an omitted contract.
4. Compare exact contracts literally. Strings, identifiers, types, representations, formats, ordering,
   and required dynamic values are not satisfied by semantic equivalents.
5. Inspect the final artifact or state, not only the command that attempted to produce it.
6. Run narrow checks first, then risk-required regressions. Algorithm tests do not replace API, error, state, or
   protocol verification.
7. When authoritative evidence provides expected and actual behavior, classify logic, boundary, environment/oracle,
   or contract-conformance before changing the solution.
8. Separate facts, inferences, skipped checks, and blockers; preserve unrelated state and sensitive content.
9. Report risk precisely. Unmet, partial, missing, conflicting, blocked, or exact-contract-mismatched work is not complete.

Before implementation and after validation, check without inventing requirements:

- public signatures, visibility, and types;
- input/output units, grammar, encoding, boundaries, shape, serialization, and framing;
- null, invalid-input, error type/code/text, and required dynamic values;
- state, side effects, ordering, compatibility, mutation scope, and non-functional constraints;
- explicit examples that visible tests do not cover.

Retain each authoritative source. Self-invented APIs/tests remain inference. Keep the checklist compact for simple,
low-risk tasks.

Retain every validation attempt. Report selected, ignored, and discovered test counts only when the tool provides
reliable structured evidence; one selected test is not a complete test-suite claim. A later passing attempt does not
erase the earlier failure, and a later failure is not covered by an earlier pass.

Prefer reproducible evidence such as test output, structured state, or a rendered artifact.

## Recovering actionable evidence from noisy failed checks

If a failed build or test already exposes an actionable error, use that evidence or run a smaller targeted check; do
not mechanically rerun it. If its bounded result is truncated or lacks an actionable failure location, rerun the same
or a smaller check at most once using the current shell:

1. In one `execution_run` command, create a unique temporary log and redirect both stdout and stderr to it.
2. Save the check's exit code before any search or cleanup command can replace it.
3. Search exact anchors from existing evidence first, such as a failed test, exception, source file, `Caused by:`, or
   `COMPILATION ERROR`. If none is known, use only a small set of framework failure markers.
4. Return at most 20 matches with 3-5 surrounding lines and a bounded total result. Do not `cat` the full log or use
   `tee` to echo it into model context. If needed, try at most one more specific query or a bounded tail.
5. Delete the temporary log and exit with the original check status. Do not rely on a shell variable surviving into a
   later Tool Call.

Select redirection and search syntax for the disclosed shell: for example, `rg`/`grep` on POSIX or `Select-String` on
PowerShell. If the rerun passes, retain both facts and treat the difference as possible flakiness or environment change;
the later pass does not erase the earlier failure.
