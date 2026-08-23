---
name: result-verification
description: Verify a claimed result against observable evidence and explicit completion criteria.
license: Apache-2.0
metadata:
  haifa.version: 1.1.0
---

# Result verification

Use this Skill before claiming that a non-trivial task is complete.

1. Restate the requested outcome and its acceptance criteria.
2. Inspect the final artifact or state, not only the command that attempted to produce it.
3. Run the narrowest relevant checks first, then the broader regression checks required by risk.
4. Separate confirmed facts, inferred conclusions, skipped checks, and blocked checks.
5. Check that unrelated state was preserved and sensitive content was not exposed.
6. Report remaining risk precisely. A partial or blocked result must not be described as complete.

Before implementation and again after validation, check the generic contract:

- public signatures, visibility, and types;
- input and output units and numeric boundaries;
- null, invalid-input, error-type, and exact-text requirements;
- state changes, side effects, and ordering;
- explicit examples that visible tests do not cover.

Retain every validation attempt. Report selected, ignored, and discovered test counts only when the tool provides
reliable structured evidence; one selected test is not a complete test-suite claim. A later passing attempt does not
erase the earlier failure, and a later failure is not covered by an earlier pass.

Prefer reproducible evidence such as test output, structured state, or a rendered artifact.
