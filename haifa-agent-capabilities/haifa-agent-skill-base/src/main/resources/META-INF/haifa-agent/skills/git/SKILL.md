---
name: git
description: Inspect and change a local Git repository with the system git CLI. Use for repository status, diffs, history, branches, staging, commits, worktrees, remotes, fetch, pull, push, merge, rebase, or conflict workflows.
license: Apache-2.0
metadata:
  haifa.version: 1.0.0
  haifa.requires.bins: git
  haifa.requires.tools: execution_run
allowed-tools: execution_run
---

# Git CLI

Use `execution_run` to invoke the system `git` executable in the authorized workspace. This `SKILL.md` contains all required instructions and has no readable auxiliary resources; do not call `skill_resource_read` to guess resource paths.

1. **Inspect before mutating**: Read applicable repository instructions (e.g. `AGENTS.md`, `CONTRIBUTING.md`), run `git status --short --branch`, check remotes, and protect unrelated user changes.
2. **Respect repository boundaries**: In workspaces with separate repositories (such as root repo, docs, or test configuration), inspect, stage, commit, and push each repository independently; never mix changes across repository boundaries.
3. **Execution context**: Invoke the bare system `git` executable discovered from the trusted host `PATH`; do not select another executable path, wrap it with environment assignments, or override behavior with `git -c`.
4. **Command discipline**: Use explicit non-interactive commands and bounded output. When the product exposes `operationFamily`, use `INSPECT` for status/history, `DIFF` only for `git diff`, and `MUTATE` for repository changes.
5. **Precise staging**: Prefer path-scoped operations. Stage only task-approved exact paths instead of `git add .` or broad globs. Inspect both unstaged diff (`git diff`) and staged diff (`git diff --staged`) before committing.
6. **Validation before delivery**: Complete verification matching the modified scope before committing or delivering. Accurately distinguish passed, failed, skipped, timed-out, and environment-blocked checks.
7. **User authorization**: Create commits or push to remote branches only when explicitly requested or authorized by the user. Follow repository branch, naming, and commit message conventions.
8. **Credential hygiene**: Reuse the operating system user's existing Git configuration, credential helper, SSH agent, and environment. Never read, copy, request, print, or persist tokens, private keys, or credential files.
9. **Policy and sandbox boundaries**: All commands remain subject to Policy, Approval, Workspace, Sandbox, and network boundaries. Treat reset, clean, checkout/restore of user changes, force push, branch deletion, and history rewrites as destructive.
10. **Authoritative fact verification**: Do not infer success from exit code alone when a state check is available. When a mutating operation has an unknown outcome (e.g. timeout or network interruption), query authoritative facts (`git rev-parse HEAD`, `git status`, or remote refs via `git ls-remote`) first; blind replay is strictly prohibited.
11. **Final state confirmation**: Separately confirm the local commit SHA and remote branch ref. A commit does not imply a push, and a push does not imply a pull request.
12. **Blocker reporting**: If Git is missing, the directory is not a repository, authentication is unavailable, or the operation is ambiguous, report the exact bounded blocker without inventing a fallback credential flow.

Prefer `git --version`, `git rev-parse`, `git status --porcelain`, `git --no-pager diff --no-color`, `git log`, `git show`, and `git blame` for reads. Use branch, switch, worktree, stash, merge, rebase, and cherry-pick only when the task requires their state changes. Never modify global Git configuration.

Do not call a command-specific `git.*` Tool; Git is a CLI workflow over `execution_run`.
