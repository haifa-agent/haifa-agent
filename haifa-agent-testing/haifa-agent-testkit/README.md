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

约束：

- 测试辅助行为必须确定、可重复且默认不访问外部服务；
- 不为方便测试而复制产品状态机、授权逻辑或 Provider 协议实现；
- 不读取环境中的 Secret，不记录完整 Prompt、reasoning 或原始 Provider 响应；
- 产品模块不得依赖本模块；
- 当前模块不作为发布制品部署。

Task 02 没有把 SQLite、Cursor Codec 或 Subscription 状态机复制到 Testkit；共享 Journal 契约由
Adapter 相邻测试直接对内存与 SQLite 两个实现执行。等 Task 03 至少有两个 Transport 实现/装配消费
相同 Fixture 时，再把 transport-neutral Fixture 提升到 Testkit/TCK。
