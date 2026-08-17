# Haifa Agent 测试领域术语表

本文统一 `haifa-agent-testing` 相关英文术语的中文翻译，并用简短说明描述它们在当前测试体系中的
职责。代码、YAML、命令行参数和稳定标识继续使用英文；中文文档首次出现术语时，推荐采用
“中文（English）”形式。

## 1. 测试模块

| 模块 | 推荐中文 | 简单说明 |
| --- | --- | --- |
| `haifa-agent-testing` | 测试基础设施聚合层 | 位于 Maven Reactor 末端，统一组织测试模块，不承载生产业务代码 |
| `haifa-agent-test-harness` | 测试执行台 | 提供配置解析、执行编排、预算门禁、证据收集等可执行测试能力 |
| `haifa-agent-test-fixtures` | 共享测试固件 | 保存小型、确定性、安全且可复用的测试输入 |
| `haifa-agent-transport-tck` | 传输层技术兼容性测试套件 | 验证不同 Transport 实现是否遵守同一公共契约 |
| `haifa-agent-integration-tests` | 集成测试模块 | 验证多个真实模块组合后的行为，默认不访问公网 |
| `haifa-agent-e2e-tests` | 端到端测试模块 | 从 CLI 等产品入口一直验证到最终结果 |

`TCK` 是 `Technology Compatibility Kit` 的缩写，推荐翻译为“技术兼容性测试套件”。

## 2. 测试内容

| 英文 | 推荐中文 | 简单说明 |
| --- | --- | --- |
| Test Case | 测试用例 | 一个有明确输入、行为和预期结果的测试场景 |
| Case ID | 用例编号 | 测试用例的稳定标识，例如 `CP-01` 或 `04` |
| Catalog | 测试用例目录 | 全部标准测试用例的统一清单，相当于“题库” |
| Fixture | 测试固件 / 测试输入 | 执行测试前准备好的文件、Workspace 或数据 |
| Acceptance | 验收规则 | 判断任务是否完成的明确条件 |
| Oracle | 测试判定器 | 判断实际结果是否正确的规则或程序 |
| Grader | 评分器 | 对任务完成质量进行评分 |
| Selector | 测试选择器 | 把 Case 映射到具体 Maven 模块和测试类 |
| Scenario | 测试场景 | 对业务行为或用户操作过程的整体描述 |
| Hidden Case | 隐藏用例 | 不向被测 Agent 公开完整验收细节的测试用例 |

关系如下：

```text
Catalog（测试用例目录）
  └── Case（测试用例）
       ├── Fixture（测试输入）
       ├── Acceptance（验收条件）
       ├── Oracle（结果判定）
       └── Grader（质量评分）
```

## 3. 测试选择与环境

| 英文 | 推荐中文 | 简单说明 |
| --- | --- | --- |
| Suite | 测试套件 | 从 Catalog 中选择一批 Case，并规定重复次数和预算 |
| Matrix | 测试矩阵 | 平台、模型、PTY、Sandbox 等运行维度的组合集合 |
| Combination | 矩阵组合 | Matrix 中一个可实际运行的具体组合 |
| Host Profile | 主机配置档案 | 当前操作系统、工具链和隔离能力的受控声明 |
| Environment | 测试环境配置 | 测试需要的公开配置和 Secret 引用 |
| Budget | 测试预算 | 最长时间、Token、模型调用次数和费用上限 |
| Repetition | 重复执行 | 为验证稳定性而主动重复，不等于失败重试 |
| Retry | 失败重试 | 一次执行失败后重新尝试 |
| Blocking | 阻断型 | 该 Case 失败会使整个 Suite 失败 |
| Non-blocking | 非阻断型 | 失败会被记录，但不会提前终止其他 Case |

示例：

```text
Suite：选择 Case 04、07，各执行一次
Matrix Combination：macOS + DeepSeek + Unix PTY + Seatbelt
Budget：并发 1，费用最多 10 CNY
```

## 4. 计划与授权

| 英文 | 推荐中文 | 简单说明 |
| --- | --- | --- |
| Plan | 执行计划 | 列出本次准备执行的 Case、环境、版本和预算；生成 Plan 不调用模型 |
| Plan SHA | 执行计划指纹 | 对完整 Plan 计算的 SHA-256，用于确认获批计划没有变化 |
| Preflight | 执行前检查 | 创建批次和外部调用前检查环境、仓库、Secret 与预算 |
| Authorization | 执行授权 | 明确批准某个 Plan、Case 范围和费用上限 |
| Cost Ceiling | 费用上限 | 本次测试允许消耗的最高估算费用 |
| Fail Closed | 默认拒绝 | 信息不完整或校验失败时停止执行，不自动降级绕过 |
| Revision | 仓库版本 | 测试绑定的精确 Git Commit |
| Baseline | 测试基线 | 用于比较结果的冻结版本或历史证据 |

推荐授权流程：

```text
生成 Plan
  → 审阅 Case、Matrix、Revision 和预算
  → 批准 Plan SHA
  → 执行前检查
  → 完全一致才允许执行
```

Plan SHA 不是 Secret，可以进入日志和测试报告。Suite、Matrix、Case、预算或仓库 Commit 任一变化，
都应生成新的 Plan SHA，并重新获得授权。

## 5. 测试执行组件

| 英文 | 推荐中文 | 简单说明 |
| --- | --- | --- |
| Testkit | 测试工具包 | 出现至少两个独立消费者时才建立的共享测试辅助模块；当前没有独立制品 |
| Launcher | 测试启动器 / 启动脚本 | 准备路径、环境变量和参数，然后启动 Runner |
| Runner | 测试运行器 | 加载 Suite、执行 Case、收集并汇总结果 |
| Harness | 测试编排框架 | 管理测试计划、Campaign、阶段门禁和证据生命周期 |
| Coordinator | 测试协调器 | 按顺序协调 Case、Probe、Executor 和 Collector |
| Executor | 执行器 | 实际启动某个测试、CLI 或外部进程 |
| Collector | 收集器 | 收集 Usage、Trace、SQLite 状态等测试数据 |
| Aggregator | 汇总器 | 汇总多个 Case 或 Repeat 的结果 |
| Scanner | 扫描器 | 检查 Evidence 是否包含 Secret 等敏感内容 |
| Finalizer | 证据终结器 | 生成 Manifest，并把 Evidence 设置为只读 |

典型调用关系：

```text
Launcher（启动脚本）
  → Runner / Harness（运行器 / 编排框架）
     → Executor（执行测试）
     → Collector（收集结果）
     → Aggregator（汇总结果）
     → Finalizer（封存证据）
```

## 6. 测试类型

| 英文 | 推荐中文 | 简单说明 |
| --- | --- | --- |
| Unit Test | 单元测试 | 验证单个类或小范围逻辑 |
| Contract Test | 契约测试 | 验证公共接口或协议语义 |
| Integration Test | 集成测试 | 验证多个真实模块协作 |
| E2E Test | 端到端测试 | 从产品入口验证到最终结果 |
| Live Test | 真实外部服务测试 | 访问真实模型或第三方 Provider，可能产生费用 |
| Smoke Test | 冒烟测试 | 用最小场景快速判断基本链路是否可用 |
| Bring-up Test | 首次通路验证 | 在新平台或新环境中首次接通完整真实链路 |
| Regression Test | 回归测试 | 确认修改没有破坏已有行为 |
| Architecture Test | 架构测试 | 验证模块依赖和分层边界 |
| Compatibility Test | 兼容性测试 | 验证实现是否遵守公共规范 |
| Deterministic Test | 确定性测试 | 输入固定、结果稳定，不依赖真实外部服务 |

`Bring-up Suite` 推荐翻译为“首次通路验证套件”。“窄 Bring-up Suite”属于工程口语，正式文档推荐
写成“小范围真实链路验证套件”。

## 7. 关键路径与自主交付

| 英文 | 推荐中文 | 主要目标 |
| --- | --- | --- |
| Critical Path | 关键路径 | 验证模型、文件修改、审批、MCP、持久化等核心功能链路 |
| Autonomous Delivery | 自主交付 | 验证 Agent 能否自主完成完整开发任务并产出可验收结果 |

两者关注的问题不同：

```text
Critical Path（关键路径）：
模型调用、工具调用、审批等关键能力是否正常？

Autonomous Delivery（自主交付）：
Agent 能否理解任务、修改代码、运行测试并完成最终交付？
```

推荐组合翻译：

| 英文 | 推荐中文 |
| --- | --- |
| Critical Path Suite | 关键路径测试套件 |
| Critical Path Runner | 关键路径测试运行器 |
| Autonomous Delivery Harness | 自主交付测试编排框架 |
| Autonomous Delivery Campaign | 自主交付测试批次 |
| Autonomous Delivery Gate | 自主交付测试门禁 |

## 8. 自主交付专用术语

| 英文 | 推荐中文 | 简单说明 |
| --- | --- | --- |
| Campaign | 自主交付测试批次 | 绑定仓库版本、Matrix 和历史基线的一次完整测试活动 |
| Phase | 测试阶段 | 将大型自主交付测试拆分成多个可独立审计的阶段 |
| Phase 0 | 固件完整性阶段 | 验证全部 Case 的 Fixture 和摘要，不访问模型 |
| Phase 1 | 基础自主交付阶段 | 验证基本 Coding 任务能力 |
| Phase 2 | 分析与修复阶段 | 增加只读分析等前置验证 |
| Phase 3 | 综合能力阶段 | 执行更多 Case、Trace Replay 和能力矩阵汇总 |
| Gate | 测试门禁 | 某个阶段继续向后推进前必须满足的条件 |
| Bring-up Suite | 首次通路验证套件 | 用少量 Case 首次验证真实 Provider 链路 |
| Stub Gate | 桩服务门禁 | 使用本地模拟 Provider 验证平台链路，不产生外部费用 |
| Deterministic Probe | 确定性探针 | 不访问 Provider 的环境或功能预检 |
| Trace Replay | 调用轨迹回放 | 使用已有 Trace 验证分析与结果一致性 |
| Capability Matrix | 能力矩阵 | 汇总不同 Case 所验证的 Agent 能力 |

## 9. 模拟实现

| 英文 | 推荐中文 | 简单说明 |
| --- | --- | --- |
| Stub | 桩实现 | 按预设响应返回结果，主要验证调用链路 |
| Fake | 仿真实现 | 具有简化但真实可运行的业务逻辑 |
| Mock | 模拟对象 | 重点验证调用次数、参数和交互行为 |
| Loopback Stub | 本地回环桩服务 | 在本机提供模拟服务，不访问公网 |
| Provider | 外部能力提供方 | DeepSeek、Aliyun 等模型或服务提供者 |
| Adapter | 适配器 | 把外部 Provider 协议转换为项目内部接口 |

## 10. 测试证据与结果

| 英文 | 推荐中文 | 简单说明 |
| --- | --- | --- |
| Evidence | 测试证据 | 测试过程产生的报告、Trace、Usage 和状态文件 |
| Evidence Root | 测试证据根目录 | 仓库之外保存测试产物的目录 |
| Manifest | 证据清单 | 记录 Evidence 文件路径及其 SHA-256 |
| Integrity | 完整性 | 证明测试输入或结果没有被意外修改 |
| Summary | 测试摘要 | 一个 Gate 或 Suite 的最终概览 |
| Result Projection | 结果标准化投影 | 把不同测试系统的结果映射为统一结构 |
| Native Status | 原生状态 | 具体测试框架产生的原始状态 |
| Provider Usage | 模型用量 | 输入 Token、输出 Token、模型调用次数等 |
| Secret Scan | 凭据泄漏扫描 | 检查 Evidence 是否意外包含 API Key |
| Repository Stability | 仓库状态稳定性 | 测试前后 Commit 和工作区状态是否一致 |
| Read-only Evidence | 只读测试证据 | 测试结束后禁止继续修改的证据目录 |

`Result Projection` 只负责统一结果表达，不能覆盖 Maven、Suite Runner 或 Autonomous Delivery Gate
的原生状态，也不能把跳过或未执行映射为通过。

## 11. 常见结果状态

| 状态 | 推荐中文 | 含义 |
| --- | --- | --- |
| `PASS` | 通过 | 所有要求均满足 |
| `FAIL` | 失败 | 测试完成，但结果不符合要求 |
| `ERROR` | 执行错误 | 测试程序或环境出现异常 |
| `SKIPPED` | 已跳过 | 测试被条件判断跳过，不能算通过 |
| `NOT_RUN` | 未执行 | 测试没有真正启动 |
| `TIMEOUT` | 超时 | 超过时间上限 |
| `BLOCKED_ENVIRONMENT` | 环境阻断 | 缺少工具、权限或平台能力 |
| `PARTIAL_PASS` | 部分通过 | 部分验证完成，但不能声明整体通过 |

## 12. 整体关系

```text
Catalog（测试用例目录 / 题库）
    ↓
Suite（测试套件 / 本次选择）
    +
Matrix（测试矩阵 / 运行环境）
    +
Budget（时间和费用预算）
    ↓
Plan（执行计划）
    ↓ 计算
Plan SHA（计划指纹）
    ↓ 授权
Launcher（启动脚本）
    ↓
Runner / Harness（运行器 / 编排框架）
    ↓
Case + Fixture + Oracle / Grader
    ↓
Evidence（测试证据）
    ↓
Manifest + Summary + Result Projection
    ↓
PASS / FAIL / NOT_RUN
```

最简单的类比：

- Catalog 是题库；
- Case 是题目；
- Fixture 是答题材料；
- Suite 是本次试卷；
- Matrix 是考试环境；
- Plan 是考试安排；
- Plan SHA 是安排确认编号；
- Launcher 是开考按钮；
- Runner 是考试执行系统；
- Harness 是完整考务系统；
- Oracle / Grader 是判卷规则；
- Evidence 是答卷和监考记录；
- Manifest 是答卷档案清单。
