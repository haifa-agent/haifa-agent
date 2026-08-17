---
name: github
description: Inspect and collaborate on GitHub with the system gh CLI. Use for repositories, issues, pull requests, reviews, checks, releases, workflows, or authenticated GitHub API operations.
license: Apache-2.0
metadata:
  haifa.version: 1.0.0
  haifa.requires.bins: gh,git
  haifa.requires.tools: execution.run
allowed-tools: execution_run
---

# GitHub CLI

Use `execution_run` to invoke the system `gh` executable. Use system `git` separately for local repository and remote branch operations.

1. Check `gh auth status` before an authenticated workflow. Reuse the current operating system user's `gh` login; never create a product-specific login or token store.
2. Prefer a purpose-built `gh` command. Use `gh api` only when the CLI has no suitable high-level command, with explicit method, fields, pagination, and bounded output.
3. When the product exposes `operationFamily`, use `INSPECT` for read-only queries and `MUTATE` for any GitHub write. Keep commands non-interactive.
4. Inspect repository and branch identity before changing remote state. Verify writes with a follow-up read.
5. Obtain approval through the normal Tool policy before creating, editing, merging, closing, dispatching, uploading, or deleting GitHub state.
6. Never run commands that reveal tokens or credential files. Do not print environment variables, `gh auth token`, authorization headers, or raw secret-bearing configuration.
7. If `gh` is missing, not authenticated, lacks required scopes, or cannot reach GitHub, report the bounded blocker and the user-facing login action; do not ask for a token in chat.

Use `gh repo view`, `gh pr list/view/checks`, and `gh issue list/view` with `--repo`, `--json`, `--jq`, and explicit bounded fields. For long non-secret bodies, use a controlled temporary file with `--body-file`. Never place a secret in that file.

After an uncertain PR creation, comment, merge, or workflow dispatch, query the corresponding remote fact before considering a retry. If authentication is missing, tell the user to run `gh auth login` in their own system terminal; never start interactive login in an unattended Run.

Do not call a command-specific `github.*` Tool; GitHub collaboration is a CLI workflow over `execution_run`.
