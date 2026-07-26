# Haifa Agent Testkit

跨模块测试辅助库。当前提供稳定 Critical Path Catalog、私有 Suite Schema/Loader 和跨平台
Suite Runner；架构测试扫描 Reactor POM，禁止生产模块直接依赖所有 `haifa-agent-testing` 制品。
后续只有在两个以上模块确实需要复用时，才在这里加入 `ScriptedChatModel`、安全 Trace 断言、固定
Clock/ID、Fake Provider 等能力。

Runner 默认只生成计划。附加的 `runner` JAR 由私有 `test-config/scripts/` 调用；只有显式传入
`--execute`、安全的仓库外运行根和所需 Secret 后，才会串行执行 Catalog 中的 Maven selector。

约束：

- 测试辅助行为必须确定、可重复且默认不访问外部服务；
- 不为方便测试而复制产品状态机、授权逻辑或 Provider 协议实现；
- 不读取环境中的 Secret，不记录完整 Prompt、reasoning 或原始 Provider 响应；
- 产品模块不得依赖本模块；
- 当前模块不作为发布制品部署。
