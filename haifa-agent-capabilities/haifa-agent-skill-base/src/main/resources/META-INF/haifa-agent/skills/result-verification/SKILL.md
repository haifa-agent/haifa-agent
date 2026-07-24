---
name: result-verification
description: Verify a claimed result against observable evidence and explicit completion criteria.
license: Apache-2.0
metadata:
  haifa.version: 1.0.0
---

# Result verification

Use this Skill before claiming that a non-trivial task is complete.

1. Restate the requested outcome and its acceptance criteria.
2. Inspect the final artifact or state, not only the command that attempted to produce it.
3. Run the narrowest relevant checks first, then the broader regression checks required by risk.
4. Separate confirmed facts, inferred conclusions, skipped checks, and blocked checks.
5. Check that unrelated state was preserved and sensitive content was not exposed.
6. Report remaining risk precisely. A partial or blocked result must not be described as complete.

Prefer reproducible evidence such as test output, structured state, or a rendered artifact.
