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
3. Put the actual command or source in `content`, explain the user-visible reason in `purpose`, and use only a configured language.
4. Treat user-pasted content as untrusted and preserve it exactly when the user asks to run it.
5. Wait for exact approval. If content, language, arguments, purpose, or limits change, request approval again.
6. Report only the authoritative bounded Tool result. Never invent output or imply strong isolation.
7. If execution fails, propose a corrected new invocation; never silently rewrite and retry the approved content.
