# Architecture overview

Haifa Agent is organized as a layered Java system: domain/runtime semantics stay in pure Java, integrations adapt external systems, and applications assemble concrete products.

~~~text
Applications
    |
SDK / Spring adapters
    |
Runtime + Capability APIs
    |
Kernel / Core domain
    |
Host adapters and external integrations
~~~

## Main layers

### Kernel

The kernel contains common/domain types, Runtime API/Core, Context, Project APIs/Core/Host, and Artifact foundations.

The Core domain owns authoritative lifecycle rules such as AgentRun state transitions.

### Capabilities

Model, Tool, Skill, Credential, Memory, and Policy are separate capability families. They expose pure Java APIs and core implementations rather than requiring Spring or product applications.

### Execution

Execution API/Core coordinates command/process execution. Host adapters own physical process/filesystem interaction. The sandbox SPI is kept narrow; the current local provider is host-based and does not claim strong OS isolation.

### Integrations

Integrations implement protocol/storage boundaries such as model providers, MCP, Web, SQLite, JSONL, HTTP/SSE, and Git evidence helpers.

### SDK

The SDK provides the high-level HaifaAgent facade, Conversation/Run APIs, typed Java Tools, structured final output, and explicit product composition.

### Spring

Spring Boot is an adapter around the pure Java SDK. It handles configuration, bean discovery, and lifecycle. Spring does not own Runtime semantics.

### Applications

Coding Agent and Personal Assistant are product assemblies. Product concepts remain at the application layer unless a lower-level invariant is independently proven reusable.

## Dependency rule

High layers may depend on lower layers; lower layers must not depend on product applications.

In particular:

- Core must not depend on Spring, SQLite, provider SDKs, or product UI concepts.
- Runtime must not depend on Coding Agent or Personal Assistant domain types.
- provider integrations adapt to provider-neutral APIs.
- applications are composition roots.

## Current non-goals

The 0.1.1 baseline does not claim a distributed Worker/Control Plane platform, Graph/Workflow runtime, enterprise IAM product, or strong built-in sandbox.

See [Runtime and module boundaries](runtime-and-module-boundaries.md).
