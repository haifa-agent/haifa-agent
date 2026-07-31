# Haifa Agent Testkit

跨模块测试辅助库。当前提供稳定 Critical Path Catalog、私有 Suite Schema/Loader 和跨平台
Suite Runner；架构测试扫描 Reactor POM，禁止生产模块直接依赖所有 `haifa-agent-testing` 制品。
后续只有在两个以上模块确实需要复用时，才在这里加入 `ScriptedChatModel`、安全 Trace 断言、固定
Clock/ID、Fake Provider 等能力。

Runner 默认只生成计划。附加的 `runner` JAR 由私有 `test-config/scripts/` 调用；只有显式传入
`--execute`、安全的仓库外运行根和所需 Secret 后，才会串行执行 Catalog 中的 Maven selector。
`assets.TestingAssetPreflight` 是 Critical Path Suite Runner 与 Autonomous Delivery Harness
共同的首个治理前置步骤；它在加载 Suite/Matrix 或创建任何运行产物前校验主仓和 `test-config`
Schema 2 资产台账的生命周期、覆盖范围及引用，避免两个正式入口产生不同的 Orphan 判定。Schema 2
的目录资产默认使用 `EXACT`，不再隐式覆盖后代；只有显式 `SUBTREE` 且具有引用的可用目录才能作为
受控子树。Validator 在迁移窗口仍能解释 v1，但正式 Preflight 只接受两仓固定位置的 v2 台账。
每次 Plan/Execute 还必须通过 `--matrix-combination` 或 `HAIFA_TEST_MATRIX_COMBINATION` 选择 Suite
所引用 Matrix 中的一个组合；Runner 校验组合存在且平台与当前 Host OS 一致，并把完整组合写入
版本 3 报告。公共 `RepositoryRevision` 要求主仓和 `test-config` 是独立 Git 根；Plan 显示两仓
Commit/dirty 状态，Execute 拒绝 tracked/untracked change，并在结束后复核两仓版本未变化。
Execute 还要求外部注入 `HAIFA_TEST_APPROVED_MAX_ESTIMATED_COST_USD`，Suite 声明的费用估算上限
不得超过该独立批准额度；报告同时记录 Suite Budget 和批准额度。该门禁不等于实际计费，真实费用
仍必须根据 Provider Usage 另行汇总。
Plan 会计算版本化 `SuiteExecutionPlanFingerprint`，覆盖 Suite、预算、所选 Matrix Combination、
Case 选择及解析后的公共 Selector。Execute 要求 `HAIFA_TEST_APPROVED_PLAN_SHA256` 与该摘要完全
一致；任一配置或 Selector 漂移都会在创建运行目录和外部调用前失败。两个仓库 Commit 仍由独立
`RepositoryRevision` 字段绑定，不混入计划摘要。
Execute 结果不能只依赖 Maven 退出码：Runner 把每个 Case 的 Failsafe XML 写入独立
证据目录，要求至少一个测试实际执行且 `failures/errors/skipped` 均为零；零匹配、全部跳过、不可解析
证据和超时分别记录为 `NOT_RUN`、`SKIPPED`、`ERROR` 和 `TIMEOUT`，均不得成为 PASS。原始 XML
解析后只保留安全文件名、SHA-256 和计数，并立即删除，避免把 `user.dir`/classpath Host Path 带入
可发布证据。
Critical Path 和 Autonomous Delivery 现在共用 `process.ProcessTreeCleanup`。Tracker 从子进程启动
时持续记录后代，即使父进程先退出也能在预算结束后收敛已观察到的 Java/CLI/Tool 子进程；需要清理
介入的“成功”Maven 运行会降级为 `ERROR`，只有自然退出且最终无存活后代才能 PASS。
两套体系也共用 `evidence` 包中的 SHA-256、Manifest 和跨平台只读终结能力。每次 Critical Path
Execute 在外层运行根下创建唯一 `suite-<suite>-<epoch>-<uuid>` 证据根，Case、归一化报告和
`manifest.sha256` 全部位于其中；原始 Maven XML 删除后，整个本次证据根转为只读。外层运行根保持
可写，以便后续批次创建新的独立证据根。
`evidence.EvidenceSecretScanner` 以有界缓冲区流式扫描证据文件，能识别跨缓冲区边界的 Secret，
拒绝符号链接和非普通文件。Critical Path 在创建运行根和启动 Maven 前一次性收齐所选 Suite 的全部
Secret；执行后只把命中文件的相对路径写入 `secret-scan.json` 和 Schema 3 报告。扫描失败会使批次
失败，但不会把 Secret 值写入结果、日志或 Manifest。
`authorization.SecretPreflight` 是两套执行链共同的环境凭据预检：一次聚合全部缺失变量名，返回值
对象的诊断输出只显示已解析名称，不显示值。Autonomous Delivery 在 Gate 建目录前解析一次，并把
同一批值传给每个 Repeat 的证据扫描；Critical Path 在创建批次根和启动首个 Maven 前使用同一实现。
`run.SafeRunRoot` 是两套执行链共同的仓库外路径门禁：通过最近存在的真实祖先解析目标，避免符号链接
把表面仓库外路径落到仓库内，并拒绝文件系统根、用户 Home、仓库子目录和包含仓库的上层目录。
Campaign Parent 必须已经存在；Critical Path 外层运行根可以是待创建的新目录。

`delivery` 包提供自主交付控制面的稳定 Case Catalog、Digest 校验、Python JSON Oracle Grader、
私有 Suite Loader 与参数化 Harness。Harness 默认只打印计划；Campaign 初始化和 Gate 是显式
子命令，运行根必须位于主仓、`docs/` 和 `test-config/` 之外，已有目录一律拒绝覆盖。
Autonomous Delivery Harness 同样强制显式 Matrix Combination，并从 Matrix 派生 Host Profile；
Campaign、Phase Summary 和 Run Manifest 使用版本 3 Schema 冻结完整组合和两仓
`RepositoryRevision`，后续 Gate 不能切换组合或 Commit。
`delivery.AutonomousDeliveryGateResultAggregator` 已从 Phase Gate 单体中抽出，集中生成 Schema 3
Phase Summary、Scratch 收敛、两仓稳定性、Phase 3 Capability Matrix 和指标。该拆分保留
Autonomous Delivery 原生 Budget、`gatePassed` 和 Artifact Schema，不将其强行映射为 Critical Path
的 Maven 状态。
`delivery.AutonomousDeliveryPhasePolicy` 显式区分 Phase 1/2/3：Phase 2 强制已评审的只读 Analyze
Stub 与成功证据，Phase 3 强制确定性 Replay 和外部 Verification。Policy 所需证据即使错误标记为
`required=false` 也会 fail closed。
`delivery.AutonomousDeliveryDeterministicProbeExecutor` 串行执行 Policy 选择的只读 Analyze 或
Trace Replay。Probe 定义只保存 Module、Test Selector、证据目录和可选场景计数；Executor 统一
Maven 参数、10 分钟超时、进程树收敛、Provider Credential/Live 开关剥离、Host Path 脱敏以及
Failsafe XML 状态解析和原始 XML 清除。确定性 Probe 不继承真实 Provider 访问能力。
`delivery.AutonomousDeliveryPhaseThreeVerificationCollector` 独立生成 Verification Plan/Evidence、
Side Effect Evidence 和 Capability Matrix；Acceptance Checks 为空时 fail closed，高风险 Case 的
原子性绑定 Acceptance 与 Process/Scratch Cleanup。
`delivery.AutonomousDeliveryRepeatEvidenceCollector` 已接管每个 Repeat 的 Acceptance、Driver、
Usage、Failure/Progress/Completion、Process、Secret Scan、`result.json`、Summary 和只读终结。
执行侧只传入已计算的 Policy 结论；Collector 再叠加 Process Cleanup 与 Secret Scan，二者任一失败
都不能成为 `gatePassed=true`。
顶层 `delivery.AutonomousDeliveryGateCoordinator` 只负责 Host/Secret 前置、Policy、Probe、
Case/Repeat 循环、Result Aggregation、Baseline Comparison 和 Evidence Finalization；单次
Fixture/Workspace/Driver/Runtime/Acceptance 执行由 `delivery.AutonomousDeliveryRepeatExecutor`
承担。旧 `AutonomousDeliveryPhaseOneGate` 已移除，避免类名继续误导其 Phase 1～3 实际范围。
`result.TestResultProjection` 提供版本 1 跨 Suite Sidecar。Critical Path 和 Autonomous Delivery
各自保留原生报告、Budget 与状态，同时在只读证据中新增 `result-projection-v1.json`；投影同时记录
共同 `status` 和 `nativeStatus`，因此 TIMEOUT、SKIPPED、NOT_RUN 或 `gatePassed=false` 不会被
抹平成 PASS。投影只使用相对 Evidence Reference，不写 Host 绝对路径。

跨平台公共内核还包括显式 Host Profile、精确 Toolchain、Python/Node PTY Driver `1.1.0` Result
Contract、共享父子进程树主动收敛，以及 Manifest 后的 Evidence Finalizer。Driver 共同生成结构化
`session.cast`（asciicast v2）、状态/输入动作时间线和绑定 SHA-256 的录像元数据；普通诊断输出进入
`driver.log`，Java Contract 复核录像格式、事件顺序、大小与摘要。Campaign 和 Fixture 私有目录复用
`SecureFilePermissions`：POSIX 使用 owner-only 权限，Windows 使用当前用户独占 ACL；证据发布后
递归复核 POSIX 只读权限或 Windows ACL/DOS 只读属性。平台脚本不复制 Case、预算、Oracle 或 Gate。

`phase-1-gate --execute` 串行驱动生产 Coding Terminal，为每个 Case/Repeat 创建独立 Workspace、
SQLite、JSONL Transcript、Trace 与会话录像，并在 Workspace 外执行固定 Acceptance。Harness 从
SQLite 权威存储读取有界的安全 Runtime Event、Run Usage 和 Tool Call 事实；JSONL 只作为客户端安全
投影，不承担内部 Gate 取证。Harness 生成 Failure Cluster、Meaningful Progress、Scratch、Completion、
Secret Scan 和 Process Cleanup 证据；超时、预算越界、同类失败超过 4 次、已实际执行的命令缺少
Scratch、Scratch 清理失败或 Secret 命中均失败。每个 Repeat 和 Gate 生成 SHA-256 Manifest 后整体
设为只读。Manifest 只排除可被 Finder 异步改写、且不承载交付事实的 `.DS_Store`；Workspace、
Runtime 与其余 Gate 证据文件全部纳入摘要。

`phase-3-gate --execute` 在新的 `phase-3/build-<commit>/gate-<timestamp>/` 下复用同一隔离协议，并
为每次运行额外生成冻结 `verification-plan.json`、逐维度
`verification-evidence.json`、Workspace 前后摘要与 Scratch 清理绑定的
`side-effect-evidence.json`，以及 Gate 级能力矩阵和指标。启动真实 Provider 前先执行十场景安全
Trace Replay；隐藏 Acceptance 只在 Run 终态后运行，结果不反馈给同一 Run。Phase 3 Catalog 含
01～17，其中新增七个跨语言、失败原子性、数据库、只读、合理重试、outcome unknown 和 UNKNOWN
Intent 场景；Suite 决定实际重复次数和显式 Skip，Harness 不把 Skip 记成通过。

自主交付 Harness 的隔离 Local Native 配置保持网络禁用，并允许最多 32 个进程，以容纳
`go test -race` 等会并行启动编译器子进程的受控 Toolchain 验证；该上限不授予额外路径或网络能力。
Catalog 显式声明的 Workspace 脚本在 Fixture 物化时恢复可执行位。Scratch Gate 只统计已进入
Sandbox 的执行；参数或路径预检阶段拒绝、从未启动进程的请求仍记录为 Tool Failure，但不伪报为
“执行后缺少 Scratch”。

约束：

- 测试辅助行为必须确定、可重复且默认不访问外部服务；
- 不为方便测试而复制产品状态机、授权逻辑或 Provider 协议实现；
- 真实 Gate 只把环境 Secret 继承给生产进程，并使用共享流式扫描器检查证据；只持久化命中文件的
  逻辑路径，不持久化 Secret、reasoning 或原始 Provider 响应；
- 产品模块不得依赖本模块；
- 当前模块不作为发布制品部署。

Task 02 没有把 SQLite、Cursor Codec 或 Subscription 状态机复制到 Testkit；共享 Journal 契约由
Adapter 相邻测试直接对内存与 SQLite 两个实现执行。等 Task 03 至少有两个 Transport 实现/装配消费
相同 Fixture 时，再把 transport-neutral Fixture 提升到 Testkit/TCK。
