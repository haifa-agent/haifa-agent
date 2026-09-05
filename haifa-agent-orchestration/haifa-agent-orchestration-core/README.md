# Haifa Agent Orchestration Core

Pure Java M1/M3/M5 plus the provider-neutral M6 increment of the Incubating Orchestration API:

- canonical SHA-256 definition compilation and capability validation;
- deterministic immutable State Delta merge;
- in-memory start/resume/cancel idempotency and monotonic events;
- sequence, condition, bounded loop, fixed `ALL_OF`, wait/resume, and Agent Run gateway fixtures;
- static subgraph Definition-set compilation, explicit State mapping, linked child Workflow Runs, nested wait/resume,
  cancellation/timeout propagation, and deterministic fixed-branch merge;
- bounded dynamic candidate selection with deterministic ordering/merge, and fixed one-Action `ANY_OF` with stable
  first-success selection, loser events, base-state rollback, and fail-closed unknown outcomes;
- explicit fail-closed rejection of runtime graph discovery, recursive/missing references, excessive expansion,
  unsafe `ANY_OF` candidates, and interrupting subgraphs inside parallel branches;
- provider-neutral `DurableWorkflowRuntime`, Store/UoW ports, frozen adapter/codec binding, two-phase node result
  commit, persisted fan-out/ANY_OF cursor, restart reconciliation, idempotent commands, and outcome-unknown handling.

The in-memory runtime remains a development and contract implementation. The durable runtime requires an explicit Store
and does not select one automatically. Agent nodes use a narrow gateway returning or recovering the authoritative
`AgentRunId`; durable Agent nodes require `DurableWorkflowAgentGateway`, whose split start/await boundary commits Agent
Run creation and Attempt association in the shared UoW before waiting for terminal work. The module does not copy the
Agent Run state machine.
