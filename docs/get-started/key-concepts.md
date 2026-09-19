# Key concepts

Haifa Agent separates the model-facing execution loop from product-facing composition. The distinction matters because a model call, a user conversation, and a durable execution are not the same thing.

## HaifaAgent

HaifaAgent is the assembled, host-owned SDK facade. It owns Runtime resources and provides access to Conversation and Run APIs. It is not a single request.

## Conversation / Session

A Conversation is the SDK-facing multi-turn container backed by the Core Session identity. It stores user-visible conversation metadata while Runtime remains authoritative for Session, Turn, and Run facts.

A new user turn normally creates a new Run. Resuming an intentional pause continues the existing Run.

## AgentRun

An AgentRun is one authoritative execution with a controlled lifecycle. A Run freezes the configuration it needs so later changes to model catalogs or product defaults do not silently change an in-flight or historical execution.

The public SDK exposes snapshots/results; Runtime Core owns the execution machinery.

## ProductProfile

A ProductProfile is trusted host configuration: product identity, Agent definition reference, instructions, default run profile, budgets/limits, and Tool/Skill allowlists.

It is not an IAM system and no longer acts as a generic capability-resolution framework.

## Capabilities

Model, Tool, Skill, Memory, Credential, Policy, Artifact, Project/Workspace, Execution, MCP, and persistence are separate capabilities or integrations. A product should assemble only the capabilities it actually needs.

## Tool, Skill, and MCP

- **Tool** is the unified callable execution unit seen by the Runtime Tool Pipeline.
- **MCP** is one way to discover/import Tools; it does not create a parallel execution runtime.
- **Skill** is a controlled package of instructions/resources that may expose methods to the model. Installing a Skill does not grant host, network, credential, or Tool permissions.

## Interaction and approval

When policy returns ASK, Runtime creates a blocking Interaction bound to the exact action target. A trusted responder can approve or reject it. Approval does not create a reusable universal permission grant.

## Persistence and recovery

Haifa Agent persists facts that are required for the product's explicit recovery guarantees. It does not attempt to snapshot every capability or make every external side effect transparently replayable.

See [Persistence and recovery](../core-components/persistence-and-recovery.md).
