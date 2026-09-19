# Coding Agent

Haifa Coding Agent is the repository's local software-engineering product built on the same Runtime, Tool, Project, Execution, and persistence foundations exposed by the platform.

Its product philosophy is deliberately model-first: the model plans and interprets ordinary command results; Runtime enforces deterministic safety, lifecycle, budget, and execution boundaries.

## Product surface

The current product includes:

- persistent Coding Sessions;
- a tui4j/JLine terminal client;
- authorized workspace directories;
- file read/mutation Tools;
- controlled host command execution;
- direct use of system git/gh through the generic execution path;
- Tool, Skill, optional MCP, and optional Web capabilities;
- model selection through trusted product profiles;
- SQLite-backed session/runtime persistence when configured;
- explicit human approval for actions that Policy classifies as ASK.

The terminal/UI consume the CodingSessionClient product contract rather than reaching into Runtime stores directly.

## Workspace authorization

Coding Agent does not assume arbitrary filesystem authority.

Host directories are attached as explicit authorized directories with READ or DEVELOP access. File mutation and model-triggered execution require the appropriate current authorization and are checked again at execution time.

The physical path/fingerprint is host-side security state, not a model-provided authorization token.

## Commands and git

The model uses the general execution_run Tool for builds, tests, Shell commands, git, gh, and customer scripts.

Haifa does not maintain a large Java grammar that tries to understand every git/gh subcommand. A narrow fail-closed boundary still blocks known credential-disclosure/authentication mutations from flowing through the model execution path.

A non-zero process exit code remains part of the command result. It is not automatically equivalent to Runtime failure.

## Completion

Coding Agent no longer maintains a separate delivery-state machine that decides whether an open-ended coding task is "really complete".

The model sees the task, repository instructions, Tool results, exit codes, and bounded output and decides what to do next. Runtime still blocks false success for deterministic conditions such as unresolved Tool calls, pending Interactions, unknown side effects, cancellation, timeouts, resource exhaustion, and final output protocol violations.

## Execution trust

Commands run through the host-guarded execution provider. See [Execution and sandbox boundaries](../advanced/execution-and-sandbox.md).

For local development this is a controlled trusted-host model, not hostile-code containment.

## Detailed product documentation

Implementation-level configuration and terminal behavior evolve faster than this overview. Use the module documentation for exact current commands and settings:

- [Coding Agent module](../../haifa-agent-applications/haifa-agent-coding-agent/README.md)
- [Coding Terminal](../../haifa-agent-applications/haifa-agent-coding-terminal/README.md)
- [CLI](../../haifa-agent-applications/haifa-agent-cli/README.md)
