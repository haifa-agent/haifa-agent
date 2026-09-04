# Haifa Agent SQLite Orchestration Store

This pure Java integration is the explicit, optional SQLite persistence adapter for Incubating Workflow/Orchestration.
The base `haifa-agent-store-sqlite` artifact remains Runtime-only and installs V1-V11. This artifact depends on both the
base store and `haifa-agent-orchestration-core`; no existing Coding, SDK, Personal Assistant, or Runtime assembly pulls
it in automatically.

## Opt-in assembly

Add `io.haifa:haifa-agent-store-sqlite-orchestration` only to a trusted application that actively starts Workflow:

```java
try (var sqlite = SqliteWorkflowStoreFoundation.initialize(configuration, clock)) {
    var runtime = new DurableWorkflowRuntime(
            definitions,
            actionGateway,
            agentGateway,
            conditionEvaluator,
            identifiers,
            timeProvider,
            sqlite.workflows(),
            sqlite.unitOfWork(),
            persistenceBinding,
            failureInjector);
}
```

`SqliteWorkflowStoreFoundation.initialize(...)` is the only standard assembly entry. It validates the unchanged Runtime
V1-V11 history, then applies the optional Workflow extension. The presence of this artifact or a Graph Bean does not
start a Workflow and does not intercept SDK/Runtime requests.

## Schema ownership

- Runtime V1-V11 remain bundled in `haifa-agent-store-sqlite`; V11 separates the persisted Run limits.
- V12 creates `workflow_run`, `workflow_node_attempt`, `workflow_wait`, `workflow_checkpoint`, `workflow_event`,
  `workflow_outbox`, and `workflow_command`.
- V13 creates `workflow_subgraph_instance` for the stable parent/child Run relation.
- `WorkflowStoreMigrations.complete()` is the authoritative catalog used by the optional assembly;
  `currentVersion()` is derived from the last catalog entry rather than copied into backup or test constants.
- Schema migration is forward-only. Applications that never opt in remain on Runtime V11 with no `workflow_*` tables.

`SqliteWorkflowStore` freezes Definition ID/version/digest, adapter coordinate/version/configuration digest and codec
version per Run. Node results use a two-phase Attempt protocol; unresolved side effects become `OUTCOME_UNKNOWN` and
are not blindly replayed. Wait/Checkpoint, fixed branch control state, events and Outbox remain Haifa facts. No
LangGraph4j object, Provider saver payload, Prompt, Credential, reasoning content, or raw Provider response enters the
database format.

The adapter reuses the base store's thread-bound SQLite transaction through a narrow `WorkflowUnitOfWork` delegate.
Runtime and Workflow writes can therefore share one `BEGIN IMMEDIATE` transaction without making the base store depend
on Orchestration Core.

## Verification

```bash
./build-support/scripts/invoke-haifa-maven.sh --layer L2 -- \
  -pl :haifa-agent-store-sqlite-orchestration -am test
```

Tests cover the Runtime V11 to Workflow V12 upgrade, unchanged V1-V11 migration metadata, V12/V13 schema constraints,
restart recovery, crash windows, idempotency, Outbox, subgraphs, and the absence of Graph-provider dependencies.
