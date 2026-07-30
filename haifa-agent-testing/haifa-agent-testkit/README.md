# Haifa Agent Testkit

跨模块测试辅助库。当前提供稳定 Critical Path Catalog、私有 Suite Schema/Loader 和跨平台
Suite Runner；架构测试扫描 Reactor POM，禁止生产模块直接依赖所有 `haifa-agent-testing` 制品。
后续只有在两个以上模块确实需要复用时，才在这里加入 `ScriptedChatModel`、安全 Trace 断言、固定
Clock/ID、Fake Provider 等能力。

Runner 默认只生成计划。附加的 `runner` JAR 由私有 `test-config/scripts/` 调用；只有显式传入
`--execute`、安全的仓库外运行根和所需 Secret 后，才会串行执行 Catalog 中的 Maven selector。

`delivery` 包提供自主交付控制面的稳定 Case Catalog、Digest 校验、Python JSON Oracle Grader、
私有 Suite Loader 与参数化 Harness。Harness 默认只打印计划；Campaign 初始化和 Gate 是显式
子命令，运行根必须位于主仓、`docs/` 和 `test-config/` 之外，已有目录一律拒绝覆盖。

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
- 真实 Gate 只把环境 Secret 继承给生产进程，并以不输出值的字节扫描检查证据；不持久化 Secret、
  reasoning 或原始 Provider 响应；
- 产品模块不得依赖本模块；
- 当前模块不作为发布制品部署。

Task 02 没有把 SQLite、Cursor Codec 或 Subscription 状态机复制到 Testkit；共享 Journal 契约由
Adapter 相邻测试直接对内存与 SQLite 两个实现执行。等 Task 03 至少有两个 Transport 实现/装配消费
相同 Fixture 时，再把 transport-neutral Fixture 提升到 Testkit/TCK。
