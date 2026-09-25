# 安全边界

Haifa Agent 围绕显式 Trust Boundary 设计，但它不是一套完整 Security Platform。

## Secrets

Secret 不应进入 Prompt、ProductProfile、Public Diagnostics、Browser DTO 或普通 Log。

Model / Tool Integration 应使用间接 Credential Reference，并在可信 Execution Boundary 解析 Secret。

## Policy 与 Approval

PolicyDecision 是瞬态事实。

当 Request 被判定为 ASK 时，会创建绑定到 Exact Target 的 Interaction。

Approval 的含义是：“这个可信 Responder 在当前 Binding 下批准了这次精确 Action。”

它不是可复用 Bearer Token，也不会自动创建通用 IAM Permission。

## Tool Input

Tool Input 在执行前必须通过 Schema Validation。

被拒绝的不可信值不应被原样拼进任意 Diagnostic Message。

## Unknown Side Effect

如果有 Side Effect 的 Tool 已经 Dispatch，但 Outcome 变成 Unknown，Runtime 会 fail closed，而不是自动 Replay。

## Host Execution

host-guarded Provider **不是** Hostile-code Sandbox。

面对 Untrusted Multi-tenant Code 时，不能把它当作唯一 Isolation Mechanism。

这类 Threat Model 应使用外部 VM / Container / Security Boundary。

## Filesystem

Application-level Workspace / Path Validation 约束的是 Haifa “打算访问什么”。

它不等价于 Kernel-enforced Filesystem Isolation。

## Public Output 与 Logs

Public Runtime Event / Diagnostics 应只包含有界 Lifecycle Fact，不包含：

- Credential；
- 完整 Prompt；
- Protected Reasoning / Continuation；
- Raw Provider Response；
- 任意 Tool Payload；
- Host Filesystem Secret。

## Local Applications

Personal Assistant Server 面向 Loopback Trusted-local 使用；Coding Agent 是 Trusted Local Developer Tool。

如果没有额外 Deployment Security Layer，它们都不应被解释成 Hardened Public Multi-tenant Service。
