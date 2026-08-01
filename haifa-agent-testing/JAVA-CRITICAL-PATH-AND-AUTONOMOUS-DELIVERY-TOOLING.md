# Java 关键路径与自主交付测试工具选型

> 状态：工具评估与采用建议，不表示所列依赖均已引入
> 更新日期：2026-08-01

本文评估 Java 领域可用于关键路径（Critical Path）和自主交付（Autonomous Delivery）测试的工具、
库、框架与外部任务集，并说明它们与 Haifa Agent 自研 Testkit/Harness 的职责边界。具体版本仍以
项目 BOM、模块 `pom.xml` 和依赖评审为准。

## 1. 结论

- Java 关键路径测试已有成熟工具链，可以大量采用现成框架；
- 自主交付的真实 Java 任务、补丁验收和测试质量检查也有可复用资源；
- 目前没有一个 Java 原生框架能够同时提供 Plan SHA、费用授权、PTY、Sandbox、Secret Scan、
  Campaign、只读 Evidence 和跨平台进程树治理；
- `haifa-agent-testkit` 应继续作为控制与编排层，第三方工具作为底层执行能力或外部 Case 来源，不应
  直接替换现有 Harness。

## 2. 工具能力对照

| 需求 | 工具/框架 | 适合程度 | 主要用途 |
| --- | --- | --- | --- |
| 单元、契约与用例执行 | JUnit Platform / Jupiter | 很适合 | Case、Tag、参数化测试、扩展和 Suite 执行 |
| Maven 集成与端到端测试 | Maven Failsafe | 很适合 | 执行 `*IT`、`*LiveIT`、`*E2E`，管理集成测试生命周期 |
| HTTP 与模型 Stub | WireMock | 很适合 | 模拟 OpenAI-compatible、DeepSeek 和 MCP HTTP 服务 |
| 临时真实依赖 | Testcontainers for Java | 适合 | 启动数据库、消息队列、浏览器或其他容器化依赖 |
| 架构边界验证 | ArchUnit | 很适合 | 检查包依赖、模块分层和循环依赖 |
| 测试质量验证 | PIT / PiTest | 适合 | 通过变异测试判断测试能否真正发现代码错误 |
| AI 输出语义评分 | Spring AI Evaluator | 部分适合 | 相关性、事实性等补充评分 |
| Java 真实缺陷任务集 | Defects4J | 很适合做 Case 来源 | 提供真实 Java 缺陷、错误版本、修复版本和测试 |
| Coding Agent 外部基准 | SWE-PolyBench | 很适合做外部基准 | 提供包括 Java 在内的仓库级 Bug Fix、Feature 和 Refactor 任务 |

## 3. 关键路径推荐工具链

推荐组合：

```text
JUnit Platform / Jupiter
  + Maven Surefire / Failsafe
  + WireMock
  + Testcontainers（按需）
  + ArchUnit
```

### 3.1 JUnit Platform / Jupiter

适合承担叶子测试执行、参数化、Tag、扩展和断言生命周期。JUnit Platform 提供 JVM 测试框架启动
基础、`TestEngine`、命令行 Launcher 和 Suite Engine，但不负责 Haifa 的私有 Suite YAML、Matrix、
费用授权或 Evidence 治理。

官方资料：[JUnit User Guide](https://docs.junit.org/current/user-guide/)

### 3.2 Maven Surefire / Failsafe

Surefire 适合单元和相邻契约测试；Failsafe 适合 `integration-test` 与 `verify` 阶段，并允许测试失败后
继续进入 `post-integration-test` 完成环境清理。Haifa 当前的 `*Test`、`*IT`、`*LiveIT`、`*E2E`
命名仍由 Maven 配置和项目约定决定。

官方资料：[Maven Failsafe Plugin](https://maven.apache.org/surefire/maven-failsafe-plugin/)

### 3.3 WireMock

适合实现 OpenAI-compatible、DeepSeek 或其他 HTTP Provider 的本地 Stub，覆盖正常响应、错误码、
超时、流式分块和畸形载荷。WireMock 提供 JUnit Jupiter 扩展，可以由测试方法或扩展管理服务生命周期。

官方资料：[WireMock JUnit Jupiter](https://wiremock.org/docs/junit-jupiter/)

WireMock 只能验证 HTTP 协议和 Adapter 行为，不能证明真实 Provider 可用，也不能替代真实 Live Probe。

### 3.4 Testcontainers for Java

适合为集成测试创建短生命周期数据库、消息队列、Web 服务和浏览器等依赖，也支持通过
`GenericContainer` 包装自定义镜像。

官方资料：[Testcontainers for Java](https://java.testcontainers.org/)

使用边界：

- 适合验证容器化依赖和 Linux 容器环境；
- 不应替代 macOS Seatbelt、Unix PTY 或 Windows ConPTY 的平台原生证据；
- 当前 SQLite、本地文件系统等无需容器即可确定性验证的能力，不应只为统一形式而容器化；
- 引入前应确认 Docker 可用性、镜像供应链、缓存和 CI 费用。

### 3.5 ArchUnit

适合把包依赖、分层、切片和循环依赖约束写成普通 Java 测试。它与 Haifa 的模块边界测试方向一致，
用于证明低层模块没有反向依赖高层模块。

官方资料：[ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)

## 4. 自主交付可复用资源

### 4.1 Defects4J

Defects4J 是真实 Java 缺陷数据库和可扩展测试框架，提供缺陷版本、修复版本以及能够暴露缺陷的测试。
其命令行支持 checkout、compile、test、coverage 和 mutation 等操作。

官方资料：[Defects4J Documentation](https://defects4j.org/html_doc/index.html)

可以映射为 Haifa Case：

```text
Fixture：缺陷版本
Prompt：问题描述或受控任务说明
Acceptance：相关测试和回归测试全部通过
Oracle：补丁修复目标缺陷且没有破坏既有行为
```

适合用途：

- 扩充 Java Bug Fix Case；
- 构建可重复的真实缺陷基线；
- 比较不同模型或 Agent 版本的修复成功率。

不覆盖的能力：模型调用、终端驱动、Sandbox、费用预算、Secret 和 Evidence 治理。

### 4.2 SWE-PolyBench

SWE-PolyBench 是多语言仓库级 Coding Agent 基准，包含 Java、JavaScript、TypeScript 和 Python 的
Bug Fix、Feature 与 Refactor 任务，并提供实例级容器镜像、补丁验证和结果汇总。

官方资料：[SWE-PolyBench](https://github.com/amazon-science/SWE-PolyBench)

适合用途：

- 引入比合成 Fixture 更真实的 Java 仓库任务；
- 与外部 Coding Agent 基准进行可解释比较；
- 作为 Phase 3 或离线评估的候选任务来源。

采用限制：其主要评测运行器是 Python + Docker，不是 Java 库；不能直接提供 Haifa 所需的 Plan SHA、
CNY 预算、Seatbelt/PTY、Secret Scan 和只读 Evidence。若采用，应通过外部资产导入和版本冻结流程，
而不是把完整数据集复制进主仓。

### 4.3 PIT / PiTest

PIT 会对 Java 字节码注入变异并重新运行测试。如果变异后测试仍通过，说明测试或验收规则可能不足以
识别行为错误。

官方资料：[PIT Mutation Testing](https://pitest.org/)

适合用途：

- 审查 Autonomous Delivery Acceptance/Oracle 的有效性；
- 判断新增测试是否只提高覆盖率而没有增强错误检测能力；
- 为重点 Java Case 建立 mutation score 基线。

PIT 用于评价“测试是否有力量”，不负责启动 Agent，也不应成为每个 Live Gate 的强制步骤。

### 4.4 Spring AI Evaluator

Spring AI 提供 `Evaluator` 以及相关性、事实性评估实现，适合对自然语言输出进行补充评分。

官方资料：[Spring AI Evaluation Testing](https://docs.spring.io/spring-ai/reference/api/testing.html)

在 Coding Agent 中，LLM Judge 只能作为补充，不应替代以下确定性事实：

- 编译和测试结果；
- 隐藏 Acceptance；
- 文件变更范围；
- Git Diff 和仓库稳定性；
- Sandbox 与安全策略；
- Provider Usage 和预算证据。

此外，Spring AI Evaluator 不应进入纯 Java 底层模块；只有明确需要 Spring 的 Adapter、Application 或
独立评估边界才可考虑使用。

## 5. Haifa Agent 推荐分工

```text
第三方 Java 工具与任务集
├── JUnit：执行测试
├── Failsafe：集成测试生命周期
├── WireMock：Provider Stub
├── Testcontainers：临时外部依赖
├── ArchUnit：架构约束
├── PIT：验收测试质量
├── Defects4J：Java 真实缺陷来源
└── SWE-PolyBench：外部 Coding Agent 基准

Haifa Testkit / Harness
├── Catalog、Suite、Matrix
├── Plan 与 Plan SHA
├── Campaign、Phase、Gate
├── PTY 与 Sandbox
├── Provider 费用预算
├── Secret Preflight 与泄漏扫描
├── 进程树收敛和清理
├── Evidence 与 Manifest
└── Result Projection
```

第三方框架擅长执行测试、提供依赖或提供任务集；Haifa Testkit/Harness 负责把这些能力组合成安全、
受授权、可审计的 Agent 测试批次。

## 6. 分阶段采用建议

### 当前可直接复用

1. 继续使用 JUnit、Surefire/Failsafe 和 ArchUnit 作为 Java 测试执行基础；
2. 继续使用本地 Stub Server 验证 Provider Adapter；只有出现复杂 HTTP 场景时再评估 WireMock；
3. 保留当前 Testkit/Harness 的 Suite、Matrix、预算和 Evidence 控制面。

### 后续有明确收益时引入

1. 选择少量 Defects4J Java Bug 试点，转换为受版本冻结的 Autonomous Delivery Case；
2. 对高价值 Acceptance 使用 PIT 检查判定强度；
3. 需要真实容器化数据库、消息队列或浏览器时采用 Testcontainers；
4. Phase 3 稳定后再评估 SWE-PolyBench 外部基准适配；
5. 只有确定性判定无法覆盖的语义质量才增加 LLM Judge。

## 7. 不建议的做法

- 不用 JUnit Suite 直接替代私有 Suite/Matrix 与费用授权；
- 不用 Docker 测试结果代替 Seatbelt、ConPTY 或 Host Profile 的平台原生结论；
- 不把 Defects4J 或 SWE-PolyBench 完整数据集直接提交到源码仓库；
- 不让 LLM Judge 覆盖编译、测试、Hidden Acceptance 或安全失败；
- 不因为已有第三方框架而删除 Plan SHA、Secret Scan、Manifest 或仓库稳定性校验；
- 不在没有明确使用场景和模块归属时批量引入测试依赖。
