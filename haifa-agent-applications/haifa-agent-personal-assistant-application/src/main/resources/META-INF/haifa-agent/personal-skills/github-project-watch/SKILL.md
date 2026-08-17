---
name: github-project-watch
description: Read and summarize GitHub repository activity with the system gh CLI. Use in Personal Assistant for project, issue, pull request, review, workflow, release, and notification monitoring without modifying GitHub state.
license: Apache-2.0
metadata:
  haifa.version: 1.0.0
  haifa.requires.bins: gh
  haifa.requires.tools: execution.run
allowed-tools: execution_run
---

# GitHub project watch

Use `execution_run` in `COMMAND` mode with read-only system `gh` command text and an explicit user-visible purpose.

1. Confirm the repository, organization, time window, and requested activity type from available context.
2. Check `gh auth status`, then query only the minimum fields needed with bounded results.
3. Reuse the operating system user's current `gh` login. Never request, reveal, copy, or persist a token.
4. Keep this workflow read-only. Do not comment, edit, label, close, merge, dispatch, rerun, upload, or delete.
5. Cite stable GitHub URLs or identifiers in the summary and distinguish observed facts from interpretation.
6. If `gh` is unavailable, unauthenticated, unauthorized, or offline, report the blocker and the user-facing login action without starting a separate PA authentication flow.

For any requested GitHub write, stop this watch workflow and require an explicitly authorized write-capable workflow.
