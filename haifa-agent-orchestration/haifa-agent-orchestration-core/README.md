# Haifa Agent Orchestration Core

Pure Java M1 implementation of the Incubating Orchestration API:

- canonical SHA-256 definition compilation and capability validation;
- deterministic immutable State Delta merge;
- in-memory start/resume/cancel idempotency and monotonic events;
- sequence, condition, bounded loop, fixed `ALL_OF`, wait/resume, and Agent Run gateway fixtures;
- explicit fail-closed rejection of subgraph, dynamic fan-out, and `ANY_OF`.

The in-memory runtime is a development and contract implementation, not durable production persistence. Agent nodes
use a narrow gateway returning the authoritative `AgentRunId`; the module does not copy the Agent Run state machine.
