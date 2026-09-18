# Haifa Agent Base Skills

This module packages the deliberately small SDK-level Skill set distributed with Haifa Agent.
It contains `task-planning` and `result-verification`.

All Skills are instruction-only classpath resources: no scripts, process execution, network access, credentials, or
Tool grants. System `git` and `gh` are invoked directly through `execution_run`; they are not shipped as built-in
Skills, and products still need a frozen `execution_run` Tool, Workspace/network authorization, Policy, and Approval.

`result-verification` also describes a prompt-only recovery workflow for noisy failed checks: when bounded output lacks
actionable evidence, the Agent may rerun once with stdout/stderr redirected to a temporary log, search bounded context,
preserve the original exit code, and clean up. The Skill does not add a log Tool, Store, parser, or Runtime lifecycle.
