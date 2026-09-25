# 兼容性

## Java

Haifa Agent 0.1.1 面向 Java 21。

## Build

仓库包含 Maven Wrapper 3.9.15。

Repository Build 应优先使用 Wrapper，使 Contributor 使用预期 Maven 版本线。

## Spring

0.1.1 baseline 在 Spring Adapter / Application Layer 使用 Spring Boot 3.5.x。

Pure Java Kernel、Runtime、Capability API 与 SDK 不要求依赖 Spring。

## Operating Systems

项目面向 Windows、Linux 与 macOS 开发。

Host Process 行为天然会受到本地 OS 与已安装 Executable 影响。

当前 Host Execution Provider 尽量保持一致的产品契约，但不宣称各 OS 上存在完全相同的 OS-level Sandbox 能力。

## Model Providers

Provider Compatibility 取决于具体 Binding，而且变化速度通常高于 SDK API。

精确 Provider / Model / Style 兼容范围以 Integration Module README 与测试为准。

不要仅因为两个 API 看起来“兼容”就推断已经受支持。

## MCP

MCP Compatibility 取决于 Protocol Version 与 Transport。

精确支持矩阵见 [MCP 模块 README](../../haifa-agent-integrations/haifa-agent-mcp/README.md)。

## API Stability

项目仍然处于 Pre-1.0。

部分 Convenience API、Structured Output Surface、Provider Binding 与 Product API 尚未声明 Stable，可能在开发版之间变化。
