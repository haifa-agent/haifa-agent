# Haifa Agent Documentation

This directory contains the public documentation for Haifa Agent. It is versioned with the source code and should describe the behavior of the current repository, not historical design proposals.

Haifa Agent is a Java 21 Agent Runtime, SDK, and application platform. The easiest entry point is the safe-default SDK Starter; lower-level Runtime and capability APIs are available when an application needs explicit control.

## Start here

- [Installation](get-started/installation.md)
- [Quickstart](get-started/quickstart.md)
- [Configuration](get-started/configuration.md)
- [Spring Boot](get-started/spring-boot.md)
- [Key concepts](get-started/key-concepts.md)

## Core components

- [Agent Runtime](core-components/agent-runtime.md)
- [Conversations and Runs](core-components/conversations-and-runs.md)
- [Capabilities](core-components/capabilities.md)
- [Persistence and recovery](core-components/persistence-and-recovery.md)

## Advanced guides

- [Java Tools](advanced/tools.md)
- [Skills and MCP](advanced/skills-and-mcp.md)
- [Model providers](advanced/model-providers.md)
- [Structured output](advanced/structured-output.md)
- [Execution and sandbox boundaries](advanced/execution-and-sandbox.md)

## Architecture

The public architecture section intentionally stays small. It documents stable boundaries rather than internal planning history.

- [Architecture overview](architecture/overview.md)
- [Runtime and module boundaries](architecture/runtime-and-module-boundaries.md)
- [Evidence-driven design principles](architecture/design-principles.md)

## Applications

- [Coding Agent](applications/coding-agent.md)
- [Personal Assistant](applications/personal-assistant.md)

## Operations and reference

- [Error handling](guides/error-handling.md)
- [Production checklist](guides/production-checklist.md)
- [Troubleshooting](guides/troubleshooting.md)
- [Compatibility](reference/compatibility.md)
- [Security](reference/security.md)
- [Upgrading](reference/upgrading.md)
- [Release notes](reference/release-notes.md)

## Documentation authority

For current behavior, use this order of authority:

1. the source code and tests on the branch you are using;
2. the Maven reactor and module POMs;
3. adjacent module README files and architecture tests;
4. this public documentation.

Historical prompts, implementation reports, PRDs, bug retrospectives, and superseded architecture plans are intentionally not part of this directory.
