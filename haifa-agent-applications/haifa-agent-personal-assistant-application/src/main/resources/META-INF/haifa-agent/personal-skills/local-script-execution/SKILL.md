---
name: local-script-execution
version: 1.0.0
description: Run one minimal local command or script after exact user approval.
allowed-tools: execution_run
---

# Local script execution

Use this skill only when a bounded local command or script is necessary to answer the user's request.

1. Use only `execution_run`; do not claim access to a terminal, project, arbitrary working directory, executable, provider, environment, network policy, or credentials.
2. Prefer the smallest command or script that can obtain the requested observation.
3. For `COMMAND`, put the complete shell text in `content` and omit both `language` and `args`; the trusted host configuration selects PowerShell on Windows or Bash/POSIX shell on macOS/Linux.
4. For `SCRIPT`, put the source in `content`, select one configured `language`, and include `args` only when the script needs them.
5. Explain the user-visible reason in `purpose`. Treat user-pasted content as untrusted and preserve it exactly when the user asks to run it.
6. Wait for exact approval. If content, language, arguments, purpose, or limits change, request approval again.
7. Report only the authoritative bounded Tool result. Never invent output or imply strong isolation.
8. If execution fails, propose a corrected new invocation; never silently rewrite and retry the approved content.
