# Haifa Agent 文档

本目录是 Haifa Agent 的公开文档，并与源码一起版本化。这里描述的是当前仓库真实支持的行为，而不是历史设计方案或未来规划。

Haifa Agent 是面向 Java 21 的 Agent Runtime、SDK 与应用开发平台。最简单的入口是带安全默认值的 SDK Starter；当应用需要更明确的控制时，也可以直接使用更底层的 Runtime 与 Capability API。

## 从这里开始

- [安装](get-started/installation.md)
- [快速开始](get-started/quickstart.md)
- [配置](get-started/configuration.md)
- [Spring Boot](get-started/spring-boot.md)
- [核心概念](get-started/key-concepts.md)

## Core Components

- [Agent Runtime](core-components/agent-runtime.md)
- [Conversation 与 Run](core-components/conversations-and-runs.md)
- [Capabilities](core-components/capabilities.md)
- [持久化与恢复](core-components/persistence-and-recovery.md)

## Advanced Guides

- [Java Tools](advanced/tools.md)
- [Skills 与 MCP](advanced/skills-and-mcp.md)
- [Model Providers](advanced/model-providers.md)
- [Structured Output](advanced/structured-output.md)
- [Execution 与 Sandbox 边界](advanced/execution-and-sandbox.md)

## Architecture

公开 Architecture 文档刻意保持精简，重点说明稳定边界，而不是复制内部设计历史。

- [架构概览](architecture/overview.md)
- [Runtime 与模块边界](architecture/runtime-and-module-boundaries.md)
- [证据驱动的设计原则](architecture/design-principles.md)

## Applications

- [Coding Agent](applications/coding-agent.md)
- [Personal Assistant](applications/personal-assistant.md)

## 运维与 Reference

- [错误处理](guides/error-handling.md)
- [生产检查清单](guides/production-checklist.md)
- [故障排查](guides/troubleshooting.md)
- [兼容性](reference/compatibility.md)
- [安全边界](reference/security.md)
- [升级](reference/upgrading.md)
- [Release Notes](reference/release-notes.md)

## 文档事实来源

判断当前行为时，按以下优先级理解：

1. 当前分支的源码与测试；
2. Maven Reactor 与各模块 POM；
3. 相邻模块 README 与 Architecture Test；
4. 本公开文档。

历史 Prompt、开发报告、PRD、Bug 复盘和已废弃的 Architecture 方案有工程参考价值，但不会进入本目录作为公开契约。
