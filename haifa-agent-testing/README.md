# Haifa Agent Testing

`haifa-agent-testing` 是根 Reactor 末端的测试基础设施聚合层。它不承载产品运行时行为，也不改变
Kernel、Capability、Integration 或 Application 的依赖方向。

## 四层模块结构

```text
haifa-agent-testing
├── haifa-agent-test-fixtures        # 最小公共测试基础设施
├── haifa-agent-integration-tests    # 跨模块确定性契约集成
├── haifa-agent-e2e-tests            # 核心用户链路端到端回归（Critical Path）
└── haifa-agent-autonomous-delivery  # 最小自主交付能力防退化评测
```

### `haifa-agent-test-fixtures`（公共测试设施）

纯代码层面的无状态辅助工具集，无独立生命周期：

- **临时工作区与 Git 工具**：创建隔离临时目录、执行标准 `git init`/commit/diff；
- **存储 Bootstrap**：SQLite 快速内存/文件初始化、自动应用 Migration；
- **模型与工具存根**：OpenAI-compatible Loopback Stub（预设固定回复、流式 SSE、Thinking/ToolCall）、
  Fake 外部工具、测试用 Policy 规则集；
- **标准客户端装配扩展**：`StandardCodingAgentClientExtension`（JUnit 5 ParameterResolver），
  直接向测试注入 `CodingAgentClientFactory`；
- **轻量进程治理**：`ProcessTreeCleanup` 负责超时控制与子进程树平稳收敛；
- **自主交付 Fixture Catalog**：`AutonomousDeliveryCaseCatalog`、`AutonomousDeliveryFixtureStore`
  保存自包含 Fixture Package，供下游 `autonomous-delivery` 模块消费；
- **Evidence 辅助**：`EvidenceSecretScanner`、`Sha256Digests`、`EvidenceSymlinkTarget`。

### `haifa-agent-integration-tests`（跨模块确定性契约集成）

验证多个生产模块组合后的确定性契约，不调用真实付费模型，运行稳定、秒级完成，纳入日常 CI：

- **协议契约（收敛自原 `transport-tck`）**：Transport Test Driver、HTTP/JSON 协议、SSE 事件流、
  排他 Cursor、断线重连；
- **Runtime + Persistence**：SQLite 事件追加、快照持久化、进程中断恢复。

### `haifa-agent-e2e-tests`（核心用户链路端到端回归）

从用户视角验证最重要的完整链路，数量保持少而稳定（CP-01 ～ CP-11），用于大型重构和重大功能后
的快速防退化。支持双模运行：

- *Simulated 模式*：使用 Stub 模型，全自动集成进 CI 验证；
- *Live 模式*：读取环境变量传入的 API Key 时自动激活真实模型交互。

| Case | 路径 | 当前实现 |
| --- | --- | --- |
| `CP-01` | 真实模型连通与响应 | `CriticalPathClientLiveE2E#completesAgentBaselineTurn` |
| `CP-02` | 单文件缺陷修复 | `CodingAgentLiveE2E#repairsSingleFileBoundaryDefect` |
| `CP-03` | 多文件功能实现 | `CodingAgentLiveE2E#implementsMultiFileDiscountFeature` |
| `CP-04` | 首次执行失败后诊断恢复 | `CodingAgentLiveE2E#diagnosesFailedExecutionAndRecovers` |
| `CP-05` | 保留用户已有脏文件 | `CodingAgentLiveE2E#preservesUnrelatedDirtyWorkspaceContent` |
| `CP-06` | 审批拒绝且无副作用 | `CodingAgentLiveE2E#rejectedApprovalProducesNoSideEffect` |
| `CP-07` | Skill 发现、冻结与激活 | `CriticalPathClientLiveE2E#activatesReviewedSkill` |
| `CP-08` | Web Search 后 Fetch | `CriticalPathClientLiveE2E#searchesAndFetchesPublicWebContent` |
| `CP-09` | MCP 协议协商、发现与调用 | `CriticalPathClientLiveE2E#discoversAndCallsUtilityMcp` |
| `CP-10` | SQLite 权威状态与 JSONL 投影 | `CriticalPathClientLiveE2E#persistsRunToSqliteAndJsonl` |
| `CP-11` | Interaction、Event Journal 与 HITL | `InteractionEventHitlLiveE2E#completesInteractionEventAndHitlRoundTrip` |

### `haifa-agent-autonomous-delivery`（最小能力防退化评测）

最小能力评测，不承担完整 Benchmark 平台职责：

- 采用能力阶梯设计（L1 单点修复 ～ L6 跨模块复杂重构）；
- 业界 Standard Benchmark（SWE-bench Verified 等）统一由 `haifa-agent-evals` 承担；
- 当前为标准模块骨架与占位规范，历史 fixture 素材保留在 `haifa-agent-test-fixtures` 中。

## 测试范围分类

`Integration` 和 `E2E` 描述测试范围，`Live` 描述是否调用真实外部依赖：

```text
范围：Unit -> Component/Contract -> Integration -> E2E
依赖：Stub/Fake -> Local Real -> Sandbox Live
```

- **Integration**：多个真实生产组件协作；SQLite、文件系统、JSONL 和本地 MCP 使用真实实现，
  系统所有权之外的模型/Web Provider 使用 Scripted Fake 或 Stub Server；默认不访问公网。
- **Live**：窄范围验证真实模型/MCP/Web Provider 的连通性、协议兼容和错误映射；
  需要显式开关、Secret、并发与费用预算。
- **E2E**：从 CLI 等用户入口到最终 AgentRun、Tool、Artifact 和持久化结果；
  既可以是 Stub 驱动的 Simulated E2E，也可以是调用真实外部依赖的 Live E2E。

## 边界约束

- 产品模块不得依赖本目录中的模块；
- 测试模块可以按用例需要单向依赖产品模块；
- 共享 Fixture 的文件名、类型名、协议字段及测试数据都必须保持供应商中立；具体供应商由产品配置注入；
- 模块私有 Fixture 优先留在相邻模块的 `src/test/resources`；
- API Key、Token、生产数据、真实 Host Path、原始 Prompt/Provider 响应和运行生成的数据库、Trace、
  Transcript、Workspace 不得进入本目录；
- 真实模型、外部 MCP、Web Provider 和高成本 E2E 必须保持显式 opt-in，并使用独立测试凭据；
- 超时或父进程先退出时仍需使用跨平台进程树治理收敛已观察后代；
- 测试运行产物写入指定测试临时目录，不得写入代码仓库。
