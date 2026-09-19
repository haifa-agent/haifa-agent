# Execution and sandbox boundaries

Haifa Agent can execute host processes, but the current implementation is intentionally **not** an operating-system security sandbox.

## Current implementation

The Execution Broker uses the sandbox SPI with the host-guarded provider as the current local implementation.

It provides application-level process governance such as:

- controlled process creation;
- explicit working directory;
- bounded output;
- timeout and cancellation;
- process-tree cleanup;
- controlled environment handling;
- managed-process support used by capabilities such as MCP stdio.

## What it does not provide

The current host provider does not claim:

- Linux namespace isolation;
- macOS Seatbelt isolation;
- Windows AppContainer isolation;
- container isolation;
- network isolation;
- CPU/memory cgroup isolation;
- hostile multi-tenant code containment.

A working-directory check is not equivalent to kernel-enforced filesystem isolation.

## Trust model

Host execution is appropriate for trusted/local product scenarios where the operator understands that commands run under the current host account.

For hostile third-party code or strong multi-tenant isolation, place the entire Haifa Agent process inside an external security boundary such as a VM, hardened container, or another environment designed for that threat model.

## Approval is separate

Policy/Approval can require the operator to approve an exact execution request. Approval controls whether Haifa should perform the action; it does not turn a host process into a kernel sandbox.

## Coding Agent

Coding Agent uses authorized workspace directories and the shared execution path for Shell, git, gh, build tools, and customer scripts. Java does not try to reproduce every command's business semantics as a command-specific permission DSL.
