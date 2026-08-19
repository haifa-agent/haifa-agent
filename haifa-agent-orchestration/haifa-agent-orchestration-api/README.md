# Haifa Agent Orchestration API

Incubating pure Java contracts for frozen Workflow definitions, state, runs, events, waits, resume, cancellation,
and explicit `WorkflowRuntime.start(...)` routing.

- Depends only on Haifa Core/Common and the JDK.
- Does not expose Provider, Spring, Reactor, Jackson, Runtime Core, Store, Product, or credential types.
- `WorkflowStartRequest` accepts no tenant, principal, credential, Provider configuration, or assembly snapshot.
- SDK `chat()` and Runtime `start()` remain direct Agent Run entry points; this API is a separate trusted host entry.
- M1 supports sequence, condition, bounded loops, fixed `ALL_OF`, and wait/resume.
- M3 adds the explicit `RecoverableWorkflowRuntime.recover(...)` contract; it does not change SDK/Runtime routing.
- M5 adds `SUBGRAPH`, frozen child `WorkflowDefinitionRef`, explicit input/output key mapping, parent/active-child Run
  links, bounded expanded depth/nodes/branches, trusted timeout propagation, and the frozen first-version
  `FAIL_PARENT` child failure policy. Either mapping direction may be explicitly empty for control-only boundaries.
- Dynamic fan-out, `ANY_OF`, runtime Definition discovery, and arbitrary Provider node actions fail at compilation.
