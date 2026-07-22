# Haifa Agent 开发索引

本文件是仓库级 Coding Agent 入口，适用于整个仓库。它只记录稳定约定、事实来源和任务路由，不复制详细设计文档。

当前没有更深层的 `AGENTS.md` 或 `AGENTS.override.md`。若后续某个模块需要特殊约定，请在该模块目录新增精简的 `AGENTS.md`；只写与本文件不同或更具体的规则。

## 开始工作前

1. 先读根目录 [`README.md`](README.md)，确认当前已实现范围和常用命令。
2. 根据任务定位模块，并阅读对应模块的 `README.md`、`pom.xml` 和架构测试。
3. 涉及架构、模块边界或新能力时，先读 [`docs/architecture-baseline.md`](docs/architecture-baseline.md) 及对应专题文档。
4. 用代码、测试和 Maven Reactor 核对文档描述；不要把尚未实现的设计稿当成现有行为。
5. 开始修改前运行 `git status --short`，保留用户已有改动，不处理任务范围外的文件。

## 事实来源与冲突处理

按以下顺序判断项目事实：

1. 当前任务的明确需求和验收标准；
2. `docs/architecture-baseline.md` 中已确定的架构决策；
3. 模块 `README.md`、模块 `pom.xml` 和 `*ArchitectureTest.java` 中的边界约束；
4. 当前代码与自动化测试所体现的已实现行为；
5. 其他专题设计、开发提示词和开发报告。

如果专题设计与架构基线冲突，不要静默选择一方或顺手重构；明确指出冲突，并通过 ADR 或用户决策解决。若文档与代码不一致，先区分“未来设计”与“实现漂移”，再决定修改对象。

`docs/` 在当前 `.gitignore` 中被整体忽略。可将其中内容作为本地设计上下文，但在交付文档改动前必须用 `git check-ignore -v <path>` 确认其是否会进入版本控制。

## 项目快照

- 项目：面向 Java / Spring 生态的通用 Agent Runtime 与 Agent 产品开发平台。
- 当前版本：`0.1.0-SNAPSHOT`。
- 当前已落地：Agent Core 领域模型、Runtime/AgentLoop、模型能力契约与首个 OpenAI-compatible/DeepSeek 纵向集成。
- 构建基线：Java 21、Maven Wrapper 3.9.15、多模块 Monorepo。
- Java 根包：`io.haifa.agent`；Maven Group ID：`io.haifa`；Artifact ID 使用 `haifa-agent-` 前缀。
- 当前阶段不要假设 Context、Memory、Workspace、Tool/Skill/MCP 等专题设计已经完整实现。

## 模块导航

| 路径 | 职责 | 关键约束 |
| --- | --- | --- |
| [`build-support/`](build-support/README.md) | Reactor 内的 BOM 与构建支持 | 不承载业务代码 |
| [`build-support/haifa-agent-bom/`](build-support/haifa-agent-bom/README.md) | 内部模块与纯 Java 依赖管理 | 禁止引入 Spring BOM |
| [`build-support/haifa-agent-spring-bom/`](build-support/haifa-agent-spring-bom/README.md) | Spring Boot、Spring AI、Spring AI Alibaba 依赖线 | 不得被纯 Java 底层模块反向依赖 |
| [`haifa-agent-contract/`](haifa-agent-contract/README.md) | 对外 API/事件协议对象 | 只依赖 Common/JDK；不暴露 Core、Runtime、框架或 Provider DTO |
| [`haifa-agent-kernel/haifa-agent-common/`](haifa-agent-kernel/haifa-agent-common/README.md) | ID、时间、版本和基础异常 | 仅依赖 JDK；不包含产品语义 |
| [`haifa-agent-kernel/haifa-agent-core/`](haifa-agent-kernel/haifa-agent-core/README.md) | 稳定 Agent 领域模型与 Run 状态机 | 纯 Java；状态变化必须经过命名领域行为 |
| [`haifa-agent-kernel/haifa-agent-runtime-api/`](haifa-agent-kernel/haifa-agent-runtime-api/README.md) | Runtime 启动、查询、恢复、命令与交互契约 | 同步提交、异步执行；不依赖具体实现或 Provider |
| [`haifa-agent-kernel/haifa-agent-runtime-core/`](haifa-agent-kernel/haifa-agent-runtime-core/README.md) | AgentLoop、Attempt、工具/完成管线、Checkpoint 与内存存储 | 只协调 Core 行为，不复制或绕过状态机 |
| [`haifa-agent-capabilities/haifa-agent-model-api/`](haifa-agent-capabilities/haifa-agent-model-api/README.md) | Provider-neutral 模型契约 | 不依赖 Jackson、HTTP、Spring 或具体 Provider SDK |
| [`haifa-agent-capabilities/haifa-agent-model-core/`](haifa-agent-capabilities/haifa-agent-model-core/README.md) | 模型目录、选择、访问策略与健康状态 | 选择必须确定；首版无隐式 fallback/轮询 |
| [`haifa-agent-integrations/haifa-agent-model-openai-compatible/`](haifa-agent-integrations/haifa-agent-model-openai-compatible/README.md) | OpenAI Chat Completions 兼容适配及 DeepSeek 默认配置 | Provider 细节留在适配层；首版强制关闭 thinking |

固定依赖方向：

```text
common <- core <- runtime-api <- runtime-core -> model-api
                  model-api <- model-core
                  model-api <- model-openai-compatible
   ^
   └──── contract
```

高层可以依赖低层；低层不得依赖高层；禁止循环依赖。Spring Framework 从 Adapter/Integration 边界开始引入，Spring Boot 只进入 Starter、Server、Worker、Scheduler、Admin 或 Example Application。

## 设计文档路由

| 主题 | 先读文档 | 使用方式 |
| --- | --- | --- |
| 不可随意改变的决策 | [`docs/architecture-baseline.md`](docs/architecture-baseline.md) | 架构基线；关键变更先提出 ADR |
| 产品定位与总体架构 | [`docs/01-product-positioning-and-overall-architecture.md`](docs/01-product-positioning-and-overall-architecture.md) | 理解分层和产品路线 |
| 仓库模块与依赖 | [`docs/02-repository-modules-and-dependencies.md`](docs/02-repository-modules-and-dependencies.md) | 新模块、拆分或依赖调整前阅读 |
| Core 领域模型 | [`docs/03-agent-core-domain-model.md`](docs/03-agent-core-domain-model.md) | 修改领域对象、状态机或引用模型前阅读 |
| Runtime 与 AgentLoop | [`docs/04-agent-runtime-and-agent-loop.md`](docs/04-agent-runtime-and-agent-loop.md) | 修改生命周期、Attempt、Loop、Checkpoint 或完成门禁前阅读 |
| 首个模型纵向集成 | [`docs/04-post-first-integration-model.md`](docs/04-post-first-integration-model.md) | 修改模型快照、DeepSeek 或 OpenAI-compatible 适配前阅读 |
| Context 与 Memory | [`docs/05-context-memory-and-compression.md`](docs/05-context-memory-and-compression.md) | 未来设计输入；先核对代码是否已落地 |
| Project、Workspace 与文件系统 | [`docs/06-project-workspace-and-filesystem.md`](docs/06-project-workspace-and-filesystem.md) | 未来设计输入，尤其关注路径与执行安全 |
| Tool、MCP 与 Skill | [`docs/07-implementation-overview.md`](docs/07-implementation-overview.md) | 活动总览；依次阅读 `07-tool-platform.md`、`07-mcp-client-2025-11-25.md`、`07-skill-mvp-and-deer-flow-import.md`，开发按 `docs/prompts/07-phase-*.md` 顺序执行 |
| 已知待办 | [`docs/00-to-do-note.md`](docs/00-to-do-note.md) | 核对明确延期能力，避免意外启用 |

`docs/prompts/` 和 `docs/ai-coding-report/` 用于追溯阶段目标、验收过程和历史决策，不替代当前代码、测试或架构基线。

## 必须保持的实现约束

- Core 对象不是 JPA Entity，公共 API 不暴露框架或第三方 SDK 类型。
- `AgentRun` 的生命周期合法性只由 Core 的受控行为决定；Runtime 不维护第二份状态转换表。
- ID 和时间由调用方通过可注入边界生成；领域对象中不要直接调用随机生成器或 `Instant.now()`。
- Run 创建时冻结 Definition 版本和不可变配置快照引用；后续配置变化不得改变历史 Run 语义。
- `AgentRunSnapshot` 是运行中视图，`AgentRunResult` 是最终结构化结果，`AgentRunHandle` 只是查询、等待和控制的便利层。
- 公共 `AgentRunRequest` 不允许注入 Tenant、Principal 或配置快照引用；这些信息从可信 Caller Context 解析。
- Tool Call/Result 必须保留关联 ID；有副作用且结果不确定的工具不得盲目自动重放。
- 不要启用 DeepSeek thinking。启用前必须先补齐 `reasoning_content` 在消息、Tool Call、Checkpoint、恢复与安全审计中的持久语义。
- 测试和日志不得输出 API Key、完整 Prompt、凭据内容或原始供应商响应。
- 新增依赖前检查它应归属纯 Java BOM 还是 Spring BOM，并确认没有破坏模块边界。

## 修改与测试工作流

- 优先做满足需求的最小变更；不要顺带重命名、重排包结构或扩大公共 API。
- 修改公共行为时补充相邻单元测试；修改依赖边界时补充或更新 ArchUnit/Maven Enforcer 约束。
- 测试命名遵循现有 Maven 约定：Surefire 执行 `*Test.java`、`*ContractTest.java`；Failsafe 执行 `*IT.java`、`*LiveIT.java`、`*E2E.java`。
- 先运行受影响模块，再运行与 CI 一致的全仓验证。
- 行为、边界或使用方式变化时同步更新对应模块 `README.md`；对用户可见的版本变化再更新 `CHANGELOG.md`。
- 不修改生成目录 `target/`，不提交日志、IDE 配置或密钥文件。

## 构建与验证命令

Linux/macOS 使用 `./mvnw`；Windows PowerShell 使用 `.\mvnw.cmd`。

```bash
# 快速单元测试
./mvnw test

# 受影响模块及其依赖；将 artifactId 替换为目标模块
./mvnw -pl :haifa-agent-runtime-core -am test

# 应用 Spotless 格式化
./mvnw spotless:apply

# 与 GitHub Actions 一致的最终验证
./mvnw --batch-mode --no-transfer-progress -T 1C -Pci-fast clean verify

# 发布配置验证
./mvnw -Prelease verify
```

`ci-fast` 默认跳过集成测试。仅在任务明确需要时使用 `-Pci-integration` 或 `-DskipITs=false`。

真实 DeepSeek 冒烟测试还要求显式设置 `HAIFA_DEEPSEEK_LIVE_TEST=true` 和 `DEEPSEEK_API_KEY`，会访问外部服务并产生真实成本；普通开发和 CI 验证不得运行。默认只使用本地 Stub HTTP Server 测试适配器。

## 完成标准

交付前确认：

- 修改范围与任务一致，未覆盖用户已有改动；
- 模块依赖方向和纯 Java 边界未被破坏；
- 受影响测试通过，架构测试未被绕过；
- `-Pci-fast clean verify` 通过，或已明确说明无法运行的原因；
- 文档、日志和测试输出不包含秘密或敏感原文；
- 最终说明列出修改文件、验证命令和任何剩余风险。
