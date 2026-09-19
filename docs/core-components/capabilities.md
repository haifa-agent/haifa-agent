# Capabilities

Haifa Agent is intentionally compositional. Products should assemble the capabilities required by the current scenario instead of enabling a universal platform profile.

## Model

Provider-neutral model APIs are separated from provider integrations. A Run uses a frozen model snapshot and exact adapter coordinate.

## Tool

Tools are typed, schema-validated execution units. Java Tools, imported MCP Tools, Web Tools, and execution-oriented Tools ultimately use the same Runtime Tool Pipeline.

## Skill

Skills are controlled instruction/resource packages with frozen identity and progressive disclosure. Skill activation does not grant Tool, network, filesystem, or credential authority.

## MCP

MCP is an integration boundary for external Tool providers. The Haifa MCP client reviews imported definitions and maps them into the local Tool catalog. There is no second MCP-specific Tool Runtime.

## Memory

Memory is optional. The current shared Memory capability supports governed candidates/formal memories and can be backed by SQLite. Products decide whether to enable it.

## Credential

Credentials are referenced indirectly and resolved at an execution boundary. Secret values are not part of ProductProfile, Run prompts, or public diagnostics.

## Policy and approval

Policy decisions are transient. DENY > ASK > ALLOW expresses the current decision order. ASK uses a Runtime Interaction bound to the exact target; it is not a reusable IAM grant.

## Project / Workspace

Project APIs model logical product/workspace identity. Physical host filesystem access is isolated to host-side adapters. Coding Agent uses explicit authorized directories rather than assuming arbitrary filesystem access.

## Execution

Execution runs trusted host processes through the Execution Broker and Host provider. It provides process governance, not an OS security sandbox. See [Execution and sandbox boundaries](../advanced/execution-and-sandbox.md).

## Persistence

SQLite provides the current durable single-node reference implementation for Runtime and selected product capabilities. JSONL is a safe transcript projection, not the authoritative recovery store.
