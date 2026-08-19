---
name: git-delivery
description: Prepare and publish a Coding product change with system git and gh commands. Use when a coding task includes branch, commit, push, pull request, review, or CI delivery requirements.
license: Apache-2.0
metadata:
  haifa.version: 1.0.0
  haifa.requires.bins: git,gh
  haifa.requires.tools: execution.run
allowed-tools: execution_run
---

# Git delivery

Use the shared `git` and `github` workflows through `execution_run` and follow repository-local delivery instructions.

1. Inspect repository status, current branch, remotes, and applicable repository instructions before changing delivery state.
2. Keep repository boundaries separate. Stage only task-approved paths and inspect both unstaged and staged diffs.
3. Run the validation required for the changed scope and distinguish passed, failed, skipped, timed-out, and environment-blocked checks.
4. Create commits and pull requests only when the user request authorizes them. Use the required branch, target, language, title, and body conventions.
5. Reuse system Git credentials, SSH agent, and `gh auth` state. Never introduce a Coding-specific token or expose credentials.
6. Treat push, force operations, history rewrites, branch deletion, merge, release, and other remote writes according to the normal approval policy.
7. Verify the final local SHA, remote branch, pull request, and checks that are in scope. A commit does not imply a push, and a push does not imply a pull request.

Do not stage or deliver unrelated user changes.
