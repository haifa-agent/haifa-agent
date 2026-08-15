# Haifa Agent Testing

`haifa-agent-testing` 是根 Reactor 末端的测试基础设施聚合层。它不承载产品运行时行为，也不改变
Kernel、Capability、Integration 或 Application 的依赖方向。

## 分层模型

- `haifa-agent-testkit`：后续承载跨模块共享的确定性 Fake、Assertion 和安全测试辅助能力；
- `haifa-agent-test-fixtures`：保存多个测试模块共同使用、可安全进入源码仓库的小型 Fixture。
- `haifa-agent-transport-tck`：不可部署的 HTTP/SSE Contract Test Kit，验证认证授权、协议映射、
  Cursor 重连、背压、SQLite 重启恢复和 Coding/Document/Enterprise 公共语义。
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
- `haifa-agent-e2e-tests`：完整 CLI/AgentRun 路径，包含 Simulated 与显式 opt-in Live E2E。

主仓绑定的手工 Terminal 测试驱动位于 [`scripts/`](scripts/README.md)。它们保留公共测试选择器、
产品级断言与证据生成逻辑；独立 `test-config` 只负责 Suite、环境和预算编排。

真实外部 Provider 的窄 Probe 与对应 Adapter 相邻保存，例如 CP-01 的 `DeepSeekLiveIT`；需要完整
产品入口的 Live 场景进入 E2E 模块。可执行 Runtime 示例已迁入 Applications，不再作为测试模块。

自主交付泛化能力资产由 `haifa-agent-test-fixtures` 保存稳定输入和 Schema，
`haifa-agent-testkit` 保存 Catalog 校验、Suite 解析、Campaign 隔离与 Harness 编排。真实运行
产物始终写到主仓、`docs/` 和 `test-config/` 之外。Phase 前置的只读 Analyze 与 Trace Replay
使用同一 Deterministic Probe Executor；平台入口和私有 Suite 不复制 Maven 命令、超时、Secret
隔离或证据解析逻辑。

Windows Autonomous Delivery 平台链路另有独立 `PLATFORM_STUB/STUB` Gate：通过生产 CLI、真实
ConPTY 和进程内 loopback Model Stub 验证 Approval、Shell、SQLite、Secret Scan、只读 Evidence、
进程树与清理，不执行或评分 Coding Case，也不产生外部 Provider 调用和费用。其隔离结论始终为
`TRUSTED_HOST_ONLY`。

Coding Live E2E 与 Autonomous Delivery 的三端默认 Profile 统一为
`host-guarded + allow + shell auto + TRUSTED_HOST_ONLY`。macOS/Linux 的 Local Native 隔离 Gate 与
Windows unsupported Gate 继续独立统计，不与默认可信 Host 能力混算；真实 Provider 执行仍保持显式
授权、Secret 与预算门禁。

Critical Path v1 使用稳定 `CP-01`～`CP-11`：

| Case | 路径 | 当前实现 |
| --- | --- | --- |
| `CP-01` | 真实模型连通与响应 | `DeepSeekLiveIT`（OpenAI-compatible 适配器相邻测试） |
| `CP-02` | 单文件缺陷修复 | 复用 `CodingAgentLiveE2E#repairsSingleFileBoundaryDefect` |
| `CP-03` | 多文件功能实现 | 复用 `CodingAgentLiveE2E#implementsMultiFileDiscountFeature` |
| `CP-04` | 首次执行失败后诊断恢复 | 复用 `CodingAgentLiveE2E#diagnosesFailedExecutionAndRecovers` |
| `CP-05` | 保留用户已有脏文件 | 复用 `CodingAgentLiveE2E#preservesUnrelatedDirtyWorkspaceContent` |
| `CP-06` | 审批拒绝且无副作用 | 复用 `CodingAgentLiveE2E#rejectedApprovalProducesNoSideEffect` |
| `CP-07` | Skill 发现、冻结与激活 | `CriticalPathLiveE2E#activatesReviewedSkill` |
| `CP-08` | Web Search 后 Fetch | `CriticalPathLiveE2E#searchesAndFetchesPublicWebContent` |
| `CP-09` | MCP 协议协商、发现与调用 | 复用 `UtilityMcpCompatibilityLiveIT` |
| `CP-10` | SQLite 权威状态与 JSONL 投影 | `CriticalPathLiveE2E#persistsRunToSqliteAndJsonl` |
| `CP-11` | Interaction、Event Journal 与 HITL 纵向闭环 | `InteractionEventHitlLiveE2E#completesInteractionEventAndHitlRoundTrip` |

公共 Case Catalog 与 Maven selector 属于本仓库事实；私有 `test-config` 只通过 `caseId` 选择用例并提供
Suite、Matrix、Environment、Secret 引用和预算。Runner 通过 `HAIFA_TEST_CONFIG_ROOT` 读取独立配置
仓，不建立 Maven 依赖，也不把私有内容打进制品。

首版 Runner 一次执行当前主机上的一个 Suite/环境。Matrix 由 CI 或发布编排层展开为独立 Job，并在
每个 Job 中显式选择一个 Combination；Runner 校验 Combination 存在且与 Host OS 一致，不在单个
JVM 内跨平台或自动遍历 Provider。Execute 要求独立注入
`HAIFA_TEST_APPROVED_MAX_ESTIMATED_COST_USD`，Suite 的费用估算上限不得超过获批额度；报告记录两者，
实际费用核算仍由 Provider Usage 汇总和 CI 门禁负责。Runner 还为 Suite、预算、选中 Combination、
Case 和解析后的公共 Selector 生成稳定计划 SHA-256；Execute 必须与外部批准的
`HAIFA_TEST_APPROVED_PLAN_SHA256` 一致，避免配置仓或 Catalog 漂移被静默执行。

`Dev Integration` 的手动 `workflow_dispatch` 是远端治理 Plan 入口，按其冻结的 Suite/Matrix
Combination 调用私有 `test-config` wrapper 的默认 Plan
模式。该入口不传 `--execute`、Provider Secret、执行预算或批准后的 Plan SHA-256，也不会运行同一
workflow 中的 Fast/Integration/Local Native Job。私有配置仓通过仅登记在
`haifa-agent-test-config` 的只读 Deploy Key 检出；主仓 Repository Secret
`HAIFA_TEST_CONFIG_SSH_KEY` 只保存私钥，不依赖组织级 Secret 或用户 PAT。Job 中的非空检查和
私有仓 checkout 共同证明该 Key 在 Actions 中可用，Deploy Key 的 `read_only` 状态仍需从目标仓库
设置或 API 审计，不能从掩码日志推断。

Critical Path 与 Autonomous Delivery 的原生报告和 Budget 继续独立；两者额外生成版本 1
`result-projection-v1.json`，用于跨 Suite 汇总 Case、平台、版本、共同状态、原生状态、Usage 摘要、
失败分类和相对证据引用。Projection 不是新的 Gate 事实源，不得覆盖 Maven 状态或
Autonomous Delivery `gatePassed`。

`testing-assets-v2.json` 是当前公共测试资产台账。主仓只对 Autonomous Delivery Fixture、Coding
E2E Fixture 和 Testkit Schema 镜像等机器资产目录启用 Coverage Root，不机械枚举整个 Testing
源码树。目录资产默认只登记自身生命周期；只有显式 `SUBTREE` 的完整 Case/Fixture 包可以覆盖后代。
Critical Path Runner 与 Autonomous Delivery Harness 都在加载 Suite/Matrix 前要求主仓与
`test-config` 的 v2 台账，任一 Coverage Root 下新增未登记文件都会 fail closed。v1 台账仅保留旧
检出的迁移兼容，不是当前正式入口的事实源。

边界约束：

- 产品模块不得依赖本目录中的模块；
- 测试模块可以按用例需要单向依赖产品模块；
- 模块私有 Fixture 优先留在相邻模块的 `src/test/resources`；
- API Key、Token、生产数据、真实 Host Path、原始 Prompt/Provider 响应和运行生成的数据库、Trace、
  Transcript、Workspace 不得进入本目录；
- 真实模型、外部 MCP、Web Provider 和高成本 E2E 必须保持显式 opt-in，并使用独立测试凭据与预算。
- Suite Runner 在执行前必须一次性验证所选 Suite 的全部开关、Secret、运行根和预算，不能运行到
  后续 Case 才发现缺少凭据；JUnit assumption skip 不能被报告为通过；
- Suite Runner 与 Evaluation Harness 必须复用同一跨平台进程树 Tracker；超时或父进程先退出时仍需
  收敛已观察后代，存在清理介入或残留进程不能成为 PASS；
- Critical Path 与 Evaluation 必须复用同一证据 Manifest 和只读终结能力；每次执行使用唯一证据根，
  禁止覆盖历史报告或把后续批次追加到已终结目录；
- Critical Path 与 Evaluation 必须复用同一 `SafeRunRoot`，解析符号链接祖先并拒绝文件系统根、
  用户 Home、代码仓内部及包含代码仓的上层目录；
- Critical Path 与 Evaluation 必须复用同一流式 Secret Scanner；扫描只记录命中文件的逻辑路径，
  不记录 Secret 值，任一命中必须让批次失败并在只读终结前写入报告；
- 测试运行产物写入 `HAIFA_TEST_RUN_ROOT`，不得写入主仓或 `test-config`。

11 号能力 Task 02 的 Journal Contract 测试与 SQLite Adapter 相邻放置，并以同一测试方法验证
`InMemoryRuntimeStore` 与 `SqliteRuntimeEventAppender` 的 eventId、sequence、range、head/earliest、
固定 observed head 和 retention。Task 03 已建立独立 `haifa-agent-transport-tck`：主源码只声明
框架中立 Driver/Fixture，具体 Adapter、Runtime Core 与 SQLite 仅进入测试作用域。
`CP-11` 在独立运行根中程序化驱动 CLI 的拒绝、Search 批准和 Fetch 批准，并用 SQLite、Trace 与
Transcript 断言真实 Live E2E；它不复制 Transport TCK。首版不创建独立 Contract Tests 和 Evals
模块。契约测试继续位于相邻产品模块；长期行为评估在评分集、Grader 和基线形成后再加入。
