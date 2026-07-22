# Haifa Agent

Haifa Agent 是面向 Java / Spring 生态的通用 Agent Runtime 与 Agent 产品开发平台。

当前仓库处于 `0.1.0-SNAPSHOT` 的内核建设阶段，已完成 Core 领域模型、Runtime/AgentLoop、DeepSeek、阿里云百炼和火山方舟 OpenAI-compatible 模型纵向集成、Phase 1 Tool/Credential 平台、固定协议 `2025-11-25` 的 MCP Client Integration，以及经统一 Tool/Execution 链执行的本地通用 Shell 能力。

## 当前模块

```text
build-support/
  haifa-agent-bom/             纯 Java 与内部模块依赖管理
  haifa-agent-spring-bom/      Spring 生态依赖管理
haifa-agent-contract/          对外协议对象
haifa-agent-kernel/
  haifa-agent-common/          无框架基础类型
  haifa-agent-core/            稳定 Agent 领域模型
  haifa-agent-runtime-api/     Runtime 生命周期契约
  haifa-agent-context/         Context IR、预算、选择与安全 Trace
  haifa-agent-project/         Project、Workspace、逻辑路径与安全文件访问
  haifa-agent-artifact/        显式发布、内容寻址与 Provenance
  haifa-agent-runtime-core/    Runtime、AgentLoop 与本地执行实现
haifa-agent-execution/
  haifa-agent-execution-api/   Provider-neutral 命令执行契约
  haifa-agent-sandbox-api/     Sandbox 与隔离 Workspace SPI
  haifa-agent-execution-core/  ExecutionBroker、输出与 Manifest 审计
  haifa-agent-sandbox-host/    受控但非强隔离的本地主机 Provider
haifa-agent-capabilities/
  haifa-agent-credential-api/  Provider-neutral Credential 契约
  haifa-agent-credential-core/ AES-GCM Store、解析、Broker 与脱敏
  haifa-agent-tool-api/        Provider-neutral Tool 定义、Binding 与调用契约
  haifa-agent-tool-core/       Tool 目录、哈希、Schema 校验与 Provider 路由
  haifa-agent-model-api/       Provider-neutral Model 契约
  haifa-agent-model-core/      Model 目录、选择与健康状态
  haifa-agent-memory-api/      长期 Memory 领域契约与治理端口
  haifa-agent-memory-core/     Memory 审核、冲突、检索与清除实现
haifa-agent-integrations/
  haifa-agent-model-openai-compatible/  DeepSeek/百炼/方舟 OpenAI Chat 协议适配
  haifa-agent-git/                      经 Broker 执行的只读 Git 适配器
  haifa-agent-mcp/                      MCP 2025-11-25 HTTP/stdio Tool Provider
haifa-agent-applications/
  haifa-agent-project-application/      Project Context、Tool 与产品外观
  haifa-agent-cli/                      本地 Coding Agent CLI 与 Host 执行装配
```

依赖方向固定为：

```text
common <- core <- runtime-api <- runtime-core -> model-api / memory-api / memory-core / tool-api / credential-api
          \--------- project
            \        context --------^
             \----------> model-api
             credential-api <- credential-core
        credential-api <- tool-api <- tool-core -> model-api
        tool-api / credential-api / execution-api <- mcp
                       project-application -> tool-api / tool-core / credential-api / credential-core
                                           -> mcp
                  model-api <- model-core
                 memory-api <- memory-core
                  model-api <- model-openai-compatible
   ^
   └──── contract
```

`contract` 不依赖 `core`，所有已初始化的 Kernel 模块均保持纯 Java。

Tool 的内部精确身份为 `name + semanticVersion + providerId + definitionHash`，模型只看到当前 Run 冻结后的 alias、描述与输入 Schema。Runtime 的配置快照保存完整 `FrozenToolBinding`，历史 Run 不按名称重新读取当前目录。Credential 明文只通过短生命周期 Lease 交给执行边界，不进入 Prompt、Tool 参数、Checkpoint、Trace 或 Workspace。

MCP Client 固定使用官方 Java SDK 2.0.0 和协议 `2025-11-25`，只接入 `tools/list`、`tools/call`、Streamable HTTP 与受 `ExecutionBroker` 管理的 stdio。远端 Tool 先经过本地 allowlist、alias、风险和 Schema 审查，再与内建 Tool 一起进入同一 Catalog 和 Runtime Tool Pipeline；历史 Run 通过内容寻址的 `providerBindingReference` 恢复精确 MCP binding，不按远端当前目录热替换。

Project 模块提供可选的长期 Project、受控 Workspace、跨平台逻辑路径和安全只读文件 Provider。普通对话 Run 不要求 Project 或 Workspace；声明相关能力的 Run 才在 Bootstrap 时解析、授权并冻结有效 Capability。

ExecutionBroker 是本地命令的唯一应用层入口。内部组件继续使用受 executable allowlist 约束的 DIRECT argv；模型可见的 `execution.run` / `execution_run` 将完整命令文本交给本地可信配置选择的 Bash 或 PowerShell，不解析具体 CLI 语义。Host Provider 约束逻辑工作目录、环境名称、超时、有界输出和进程树，但不宣称具备网络、CPU、内存或文件系统挂载强隔离。Git inspect/status/diff 也通过 Broker；临时副本和 Git Worktree 子 Workspace 必须拥有收窄权限和独立生命周期。

Project 模块还提供可重建的 File/Java Symbol/Markdown Index 和内容寻址的版本化 ProjectConfiguration。高层 Project Application 复用现有 Context Source 与 Runtime Tool Pipeline，并提供只要求 ProjectId、消息和附件的产品入口；Workspace 仍是内部授权与执行概念。

Workspace Run 的 Runtime Checkpoint 可通过版本化 Capability Participant 引用 Metadata、Git Reference 或受限 Full Copy Snapshot；普通 Chat 的冻结能力集合为空，因此不会创建 Workspace 或空 Snapshot。恢复先重新授权当前调用者并校验 Binding、Snapshot digest 与 Drift，DIRECT Workspace 的内容漂移不会触发隐式覆盖。Artifact 只由显式 Export 产生，保存不可变内容寻址 payload 与完整 Provenance；只读 Admin 投影不返回主机路径、正文或凭据。

模型层当前通过统一 OpenAI Chat transport 和冻结 dialect 支持 Provider `deepseek`、`aliyun-bailian`、`volcengine-ark`。三家均支持同步与 SSE、final usage、Tool Call；DeepSeek 默认 thinking enabled/high，百炼和方舟由受治理模型 profile 显式开启。reasoning 只进入受保护 continuation，公共流仅投影 answer content。方舟的 Model ID/Endpoint ID 使用 `ModelReferenceKind` 冻结，不按字符串前缀猜测。模型选择在 Run 启动时进入内容寻址配置快照，目录或 Endpoint 后续变化不改变历史 Run。

Core 当前提供版本化 `AgentDefinition`、不绑定具体 Agent 的 `AgentSession`、可复现的 `AgentRun` 聚合，以及 Message、Step、ToolCall、Plan/Todo、Checkpoint、Error 和 Domain Event 最小模型。领域 ID 由调用方通过 Common 的可注入 UUIDv7 生成器创建；领域对象不读取系统时间。

Run 在创建时冻结 Definition 版本与配置快照引用，并使用完整状态机：

```text
PENDING -> QUEUED -> RUNNING
RUNNING -> SUSPENDING -> SUSPENDED -> RUNNING
RUNNING -> WAITING_INTERACTION / WAITING_APPROVAL -> RUNNING
RUNNING -> COMPLETING -> COMPLETED
非终态 -> FAILED / CANCELLED / TIMEOUT
```

Runtime API 的运行中视图为 `AgentRunSnapshot`；最终结构化结果只有 Core 中的 `AgentRunResult`；`AgentRunHandle` 只是查询、等待和控制的便利层。`start` 在 Run 与首个 `AgentRunExecutionAttempt` 持久化并提交后返回 `QUEUED` Snapshot，不等待 AgentLoop 完成。

公共 `AgentRunRequest` 只表达启动意图、类型安全 ID、输入、幂等键和受控 Overrides。Runtime 在可信 Caller Context 下解析最终 Definition/Profile 版本，并内部生成不可变配置快照；请求不能注入 Tenant、Principal 或快照引用。

Runtime Core 负责 Attempt、AgentLoop、Command、Interaction、Checkpoint 和恢复编排，但生命周期合法性仍由 Core `AgentRun` 的受控行为唯一决定。公开 Command 仅含 `PAUSE`、`CANCEL`、`TERMINATE_CHILDREN`；Interaction Response 与 Timeout、Lease Lost 等内部 Control Signal 是不同协议。

## 构建

要求 Java 21。仓库自带固定为 Maven 3.9.15 的 Wrapper，无需预先安装 Maven。

```bash
./mvnw -T 1C -Pci-fast clean verify
```

常用命令：

```bash
./mvnw test
./mvnw -pl haifa-agent-kernel/haifa-agent-core -am verify
./mvnw -Prelease verify
```

Windows PowerShell 中将 `./mvnw` 替换为 `.\\mvnw.cmd`。

## 架构文档

项目定位与模块边界见 [`docs/01-product-positioning-and-overall-architecture.md`](docs/01-product-positioning-and-overall-architecture.md)、[`docs/02-repository-modules-and-dependencies.md`](docs/02-repository-modules-and-dependencies.md)、[`docs/03-agent-core-domain-model.md`](docs/03-agent-core-domain-model.md) 和 [`docs/04-agent-runtime-and-agent-loop.md`](docs/04-agent-runtime-and-agent-loop.md)。
