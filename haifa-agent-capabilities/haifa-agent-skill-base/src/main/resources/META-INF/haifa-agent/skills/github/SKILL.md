---
name: github
description: Inspect and collaborate on GitHub with the system gh CLI. Use for repositories, issues, pull requests, reviews, checks, releases, workflows, or authenticated GitHub API operations.
license: Apache-2.0
metadata:
  haifa.version: 1.0.0
  haifa.requires.bins: gh,git
  haifa.requires.tools: execution_run
allowed-tools: execution_run
---

# GitHub CLI

Use `execution_run` to invoke the system `gh` executable for remote GitHub queries and collaboration. Use system `git` separately for local repository, diff, stage, commit, and branch push operations. This `SKILL.md` contains all required instructions and has no readable auxiliary resources; do not call `skill_resource_read` to guess resource paths.

1. **Execution context**: Invoke the bare system `gh` executable discovered from the trusted host `PATH`; do not select another executable path or wrap it with environment assignments.
2. **System authentication**: Check `gh auth status` without token-disclosure options before an authenticated workflow. Reuse the current operating system user's `gh` login; never create a product-specific login, token store, or credential file.
3. **Command selection**: Prefer purpose-built `gh` commands (`gh pr`, `gh issue`, `gh repo`, `gh run`). Use `gh api` only when the CLI has no suitable high-level command, with explicit method, fields, pagination, and bounded output.
4. **Command discipline**: When the product exposes `operationFamily`, use `INSPECT` for read-only queries and `MUTATE` for any GitHub write. Keep commands non-interactive.
5. **User authorization**: Create, update, merge, or close pull requests, add comments, or dispatch workflows only when explicitly authorized or requested by the user. Comply with repository conventions for PR target branch, title, description limits, and language.
6. **Policy and boundaries**: Obtain approval through the normal Tool policy before creating, editing, merging, closing, dispatching, uploading, or deleting GitHub state. Respect Workspace, Sandbox, and network boundaries.
7. **Credential protection**: Never run commands that reveal tokens or credential files. Do not print environment variables, `gh auth token`, `gh auth status --show-token`, authorization headers, or raw secret-bearing configuration.
8. **Authoritative fact verification**: After an uncertain PR creation, comment, merge, or workflow dispatch (such as on timeout or network drop), query authoritative remote facts (`gh pr view`, `gh pr list`, `gh run list`) before considering a retry. Blind replay of mutating operations is strictly prohibited.
9. **Verification of delivery state**: Separately confirm the remote branch ref, PR URL and number, and in-scope checks. A commit does not imply a push, a push does not imply a PR, and creating a PR does not imply CI passage or merge.
10. **Blocker reporting**: If `gh` is missing, not authenticated, lacks required scopes, or cannot reach GitHub, report the bounded blocker and prompt the user to authenticate in their own system terminal (`gh auth login`); do not ask for tokens or start interactive login in an unattended Run.

Use `gh repo view`, `gh pr list/view/checks`, and `gh issue list/view` with `--repo`, `--json`, `--jq`, and explicit bounded fields. For long non-secret bodies, use a controlled temporary file with `--body-file`. Never place a secret in that file.

Do not call a command-specific `github.*` Tool; GitHub collaboration is a CLI workflow over `execution_run`.
