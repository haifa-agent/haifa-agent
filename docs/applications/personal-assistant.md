# Personal Assistant

Haifa Personal Assistant is the local assistant application built on the shared Runtime/SDK plus product-owned Conversation, Mission, Web, and persistence layers.

It is a concrete application, not a general-purpose production Agent Server.

## Components

The application is split into:

- a pure Java Personal Assistant application layer;
- a loopback-only Spring Boot WebFlux server;
- a separate React Web application;
- SQLite product/runtime persistence;
- optional Tool, Skill, MCP, Web Search/Fetch, Memory, and host execution capabilities;
- a local read-only diagnostics/Admin surface.

The backend and Web frontend are separate deployment units.

## Local trust boundary

The Server is designed for a trusted local machine and binds to loopback by default. This must not be interpreted as a hardened internet-facing multi-tenant server.

Host execution is disabled/fail-closed unless the local deployment explicitly acknowledges the trusted-host boundary.

## Conversations and models

Model providers and model bindings are configured by the trusted server. Browser clients receive safe model/display metadata and preferences rather than raw provider endpoints, credentials, adapter internals, or frozen snapshot digests.

Credentials remain server-side.

## Mission and Deep Research

Personal Mission is an application-owned durable aggregate built above the common Runtime.

Deep Research uses product-owned planning/task/synthesis behavior while reusing the shared model, Tool, Skill, Artifact, Runtime, and persistence mechanisms.

Mission state is not pushed down into Core or Runtime merely because it is durable.

## Media

The current product supports governed image/audio inputs according to the selected model capability and server upload policy. Uploaded media is stored through product-owned bounded stores and referenced by opaque metadata rather than embedding Base64 payloads into ordinary Conversation records.

## MCP and Web

MCP and Web Search/Fetch are optional and explicitly configured. MCP does not implicitly start or discover arbitrary global servers, and Web credentials remain server-side.

## Recovery semantics

Normal persisted Interactions/intentional pauses can continue according to Runtime rules.

Unexpected loss of an actively executing owner is not transparently resumed as though nothing happened; Runtime uses the safe interrupted-execution semantics described in [Persistence and recovery](../core-components/persistence-and-recovery.md).

## Detailed product documentation

- [Application](../../haifa-agent-applications/haifa-agent-personal-assistant-application/README.md)
- [Server](../../haifa-agent-applications/haifa-agent-personal-assistant-server/README.md)
- [Web](../../haifa-agent-applications/haifa-agent-personal-assistant-web/README.md)
