# Runtime and module boundaries

This document records the public architectural boundaries that are most useful to contributors and embedders. It intentionally avoids reproducing every internal design note.

## Runtime is the execution kernel

Runtime owns:

- Run orchestration and lifecycle coordination;
- context construction;
- frozen model invocation;
- Tool Pipeline coordination;
- Interaction/Approval execution state;
- hard budgets, cancellation, and completion convergence;
- persistence ports and durable Runtime events.

Runtime does **not** own:

- product UI/navigation;
- Coding Agent workflow heuristics;
- Personal Assistant Mission domain;
- provider-specific HTTP payloads;
- Spring lifecycle;
- a universal enterprise authorization model.

## Core owns lifecycle legality

AgentRun lifecycle legality lives in Core domain behavior. Runtime invokes those behaviors; it must not duplicate the transition rules in a second state machine.

## ProductProfile is composition, not capability discovery

ProductProfile freezes trusted product identity, Agent definition reference, instructions, default run profile, budgets/limits, and Tool/Skill allowlists.

The current SDK favors explicit typed composition. It does not use a generic "resolve all capabilities by suitability" framework.

## Model boundary

Runtime talks to provider-neutral model APIs. A Run stores the exact model snapshot/adapter identity needed to execute deterministically.

Provider integrations own protocol details, authentication mapping, request/stream parsing, and provider-specific compatibility.

## Tool boundary

Tool Core owns definitions/catalog/schema validation primitives.

Runtime owns execution sequencing and safety gates.

SDK JavaTool is a convenience adapter into the same Tool Core path; MCP imports Tools into that path rather than creating a second runtime.

## Project and host boundary

Logical Project/Workspace concepts are separated from physical host access. Host-side modules own filesystem and process integration.

This keeps Path/process concerns out of the public Core domain and makes the trust boundary visible.

## Persistence boundary

Runtime depends on persistence ports, not SQLite/MyBatis.

SQLite is the durable single-node reference adapter. JSONL is a projection.

Application-specific durable state (for example Personal Mission) remains application-owned even when it shares SQLite infrastructure.

## Spring boundary

Spring begins in the Spring adapter/Starter layer. Core, Runtime, SDK, and capability APIs remain pure Java.

## Public versus internal design history

Removed experiments and superseded designs are not part of the public baseline. In particular, current code does not expose Graph/Workflow orchestration as a supported runtime capability.
