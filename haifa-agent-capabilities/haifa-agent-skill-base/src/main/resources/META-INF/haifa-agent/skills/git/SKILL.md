---
name: git
description: Inspect and change a local Git repository with the system git CLI. Use for repository status, diffs, history, branches, staging, commits, worktrees, remotes, fetch, pull, push, merge, rebase, or conflict workflows.
license: Apache-2.0
metadata:
  haifa.version: 1.0.0
  haifa.requires.bins: git
  haifa.requires.tools: execution.run
allowed-tools: execution_run
---

# Git CLI

Use `execution_run` to invoke the system `git` executable in the authorized workspace.

1. Start with `git status --short --branch` and preserve unrelated user changes.
2. Invoke the bare system `git` executable discovered from the trusted host `PATH`; do not select another executable path, wrap it with environment assignments, or override behavior with `git -c`.
3. Use explicit non-interactive commands and bounded output. When the product exposes `operationFamily`, use `INSPECT` for status/history, `DIFF` only for `git diff`, and `MUTATE` for repository changes.
4. Prefer path-scoped reads and writes. Inspect the exact diff before staging or committing.
5. Reuse the operating system user's existing Git configuration, credential helper, SSH agent, and environment. Never request, extract, print, or persist credentials.
6. Obtain approval through the normal Tool policy before commits, branch changes, network operations, or destructive commands. Treat reset, clean, checkout/restore of user changes, force push, and history rewrites as destructive.
7. Do not infer success from exit code alone when a state check is available. Re-run the smallest read-only command that verifies the requested result.
8. If Git is missing, the directory is not a repository, authentication is unavailable, or the operation is ambiguous, report the exact bounded blocker without inventing a fallback credential flow.

Prefer `git --version`, `git rev-parse`, `git status --porcelain`, `git --no-pager diff --no-color`, `git log`, `git show`, and `git blame` for reads. Use branch, switch, worktree, stash, merge, rebase, and cherry-pick only when the task requires their state changes. Stage explicit paths instead of using `git add .` by default, and never modify global Git configuration.

After an uncertain commit, inspect HEAD and status. After an uncertain push, inspect the remote ref before considering a retry.

Do not call a command-specific `git.*` Tool; Git is a CLI workflow over `execution_run`.
