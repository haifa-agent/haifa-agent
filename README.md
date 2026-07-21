# Haifa Agent

Haifa Agent 是面向 Java / Spring 生态的通用 Agent Runtime 与 Agent 产品开发平台。

当前仓库处于 `0.1.0-SNAPSHOT` 的内核建设阶段，已完成 03 领域模型和 04 Runtime/AgentLoop 的第一阶段实现。

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
  haifa-agent-runtime-core/    Runtime、AgentLoop 与本地执行实现
```

依赖方向固定为：

```text
common <- core <- runtime-api <- runtime-core
   ^
   └──── contract
```

`contract` 不依赖 `core`，所有已初始化的 Kernel 模块均保持纯 Java。

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
