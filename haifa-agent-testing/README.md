# Haifa Agent Testing

`haifa-agent-testing` 是根 Reactor 末端的测试基础设施聚合层。它不承载产品运行时行为，也不改变
Kernel、Capability、Integration 或 Application 的依赖方向。

## 分层模型

`Integration` 和 `E2E` 描述测试范围，`Live` 描述是否调用真实外部依赖。Live 不是严格位于
Integration 与 E2E 之间的层级：

```text
范围：Unit -> Component/Contract -> Integration -> E2E
依赖：Stub/Fake -> Local Real -> Sandbox Live
```

- Integration：多个真实生产组件协作；SQLite、文件系统、JSONL 和本地 MCP 使用真实实现，系统所有权
  之外的模型/Web Provider 使用 Scripted Fake 或 Stub Server；默认不访问公网、不需要真实 Secret。
- Live：窄范围验证真实模型、MCP 或 Web Provider 的连通性、协议兼容和错误映射；需要显式开关、
  Secret、并发与费用预算，不要求经过完整产品入口。
- E2E：从 CLI 等用户入口到最终 AgentRun、Tool、Artifact 和持久化结果；既可以是 Stub 驱动的
  Simulated E2E，也可以是调用真实外部依赖的 Live E2E。

## 当前子模块

- `haifa-agent-testkit`：稳定 Case Catalog、Suite Runner、断言和跨模块测试辅助能力；
- `haifa-agent-test-fixtures`：多个测试模块共享、可安全进入源码仓库的小型 Fixture；
- `haifa-agent-integration-tests`：确定性的跨模块和测试编排契约验证；
- `haifa-agent-live-tests`：真实外部 Provider 的窄范围连通性与兼容性验证；
- `haifa-agent-e2e-tests`：完整 CLI/AgentRun 路径，包含 Simulated 与显式 opt-in Live E2E。

首批 Critical Path v1 使用稳定 `CP-01`～`CP-10`：

| Case | 路径 | 当前实现 |
| --- | --- | --- |
| `CP-01` | 真实模型连通与响应 | `PrimaryModelLiveIT` |
| `CP-02` | 单文件缺陷修复 | 复用 `CodingAgentLiveE2E#repairsSingleFileBoundaryDefect` |
| `CP-03` | 多文件功能实现 | 复用 `CodingAgentLiveE2E#implementsMultiFileDiscountFeature` |
| `CP-04` | 首次执行失败后诊断恢复 | 复用 `CodingAgentLiveE2E#diagnosesFailedExecutionAndRecovers` |
| `CP-05` | 保留用户已有脏文件 | 复用 `CodingAgentLiveE2E#preservesUnrelatedDirtyWorkspaceContent` |
| `CP-06` | 审批拒绝且无副作用 | 复用 `CodingAgentLiveE2E#rejectedApprovalProducesNoSideEffect` |
| `CP-07` | Skill 发现、冻结与激活 | `CriticalPathLiveE2E#activatesReviewedSkill` |
| `CP-08` | Web Search 后 Fetch | `CriticalPathLiveE2E#searchesAndFetchesPublicWebContent` |
| `CP-09` | MCP 协议协商、发现与调用 | 复用 `UtilityMcpCompatibilityLiveIT` |
| `CP-10` | SQLite 权威状态与 JSONL 投影 | `CriticalPathLiveE2E#persistsRunToSqliteAndJsonl` |

公共 Case Catalog 与 Maven selector 属于本仓库事实；私有 `test-config` 只通过 `caseId` 选择用例并提供
Suite、Matrix、Environment、Secret 引用和预算。Runner 通过 `HAIFA_TEST_CONFIG_ROOT` 读取独立配置
仓，不建立 Maven 依赖，也不把私有内容打进制品。

首版 Runner 一次执行当前主机上的一个 Suite/环境。Matrix 由 CI 或发布编排层展开为独立 Job，并在
每个 Job 中注入对应 Provider、模型和平台环境后调用 Runner；Runner 当前只校验 Suite 引用的 Matrix
文件存在并记录其版本，不在单个 JVM 内跨平台或自动遍历 Provider。Suite 的费用字段是执行批准上限，
实际费用核算仍由 Provider usage 汇总和 CI 门禁负责。

边界约束：

- 产品模块不得依赖本目录中的模块；
- 测试模块可以按用例需要单向依赖产品模块；
- 模块私有 Fixture 优先留在相邻模块的 `src/test/resources`；
- API Key、Token、生产数据、真实 Host Path、原始 Prompt/Provider 响应和运行生成的数据库、Trace、
  Transcript、Workspace 不得进入本目录；
- 真实模型、外部 MCP、Web Provider 和高成本 E2E 必须保持显式 opt-in，并使用独立测试凭据与预算。
- Suite Runner 在执行前必须验证开关、Secret、运行根和预算；JUnit assumption skip 不能被报告为通过；
- 测试运行产物写入 `HAIFA_TEST_RUN_ROOT`，不得写入主仓或 `test-config`。

首版不创建独立 Contract Tests 和 Evals 模块。契约测试继续位于相邻产品模块；长期行为评估在评分集、
Grader 和基线形成后再加入。
