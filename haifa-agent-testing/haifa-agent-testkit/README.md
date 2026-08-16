# Haifa Agent Testkit

轻量、确定性的跨模块测试辅助库。当前只保留无外部副作用、无产品装配依赖的通用摘要能力；后续新增
Fake、固定 Clock/ID 或断言时也必须保持同一边界。

约束：

- 不依赖 SDK、CLI、SQLite、Test Fixtures 或 Test Harness；
- 不包含 Runner Main、Suite、预算授权、外部进程、真实服务访问或 Evidence 生命周期；
- 不复制产品状态机、Provider 协议或持久化实现；
- 产品模块不得依赖本模块；
- 本模块不作为发布制品部署。

可执行 Suite、Agent Profile、运行模式、交付评测和证据治理位于
`haifa-agent-testing/haifa-agent-test-harness`。
