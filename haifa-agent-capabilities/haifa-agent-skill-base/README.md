# Haifa Agent Base Skills

This module packages the deliberately small SDK-level Skill set distributed with Haifa Agent.
It contains `task-planning` and `result-verification`, plus the optional shared `git` and `github` CLI workflow
source used by products that explicitly assemble and allow those aliases.

All Skills are instruction-only classpath resources: no scripts, process execution, network access, credentials, or
Tool grants. The Git/GitHub Skills declare `execution_run` only as a compatibility hint; products still need a frozen
`execution.run` Tool, Workspace/network authorization, Policy, and Approval.
