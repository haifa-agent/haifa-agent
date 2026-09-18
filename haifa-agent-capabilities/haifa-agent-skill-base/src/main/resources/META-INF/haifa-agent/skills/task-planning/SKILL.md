---
name: task-planning
description: Turn a non-trivial request into a bounded, verifiable execution plan.
license: Apache-2.0
metadata:
  haifa.version: 1.0.0
---

# Task planning

Use this Skill when a request has multiple dependent steps, meaningful uncertainty, or several
verification points.

1. State the concrete outcome and the boundaries that must remain unchanged.
2. Split the work into the smallest independently verifiable stages.
3. Identify facts that must be inspected before a change is made.
4. Put risky, irreversible, or externally visible actions behind an explicit decision.
5. Define evidence for each stage and keep at most one stage actively in progress.
6. Re-plan when evidence invalidates an assumption; do not preserve a stale plan for appearance.

For small or obvious requests, skip formal planning and perform the work directly.
