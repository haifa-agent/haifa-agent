# Haifa Agent Testing

`haifa-agent-testing` 是根 Reactor 末端的测试基础设施聚合层。它不承载产品运行时行为，也不改变
Kernel、Capability、Integration 或 Application 的依赖方向。

当前子模块：

- `haifa-agent-testkit`：后续承载跨模块共享的确定性 Fake、Assertion 和安全测试辅助能力；
- `haifa-agent-test-fixtures`：保存多个测试模块共同使用、可安全进入源码仓库的小型 Fixture。

边界约束：

- 产品模块不得依赖本目录中的模块；
- 测试模块可以按用例需要单向依赖产品模块；
- 模块私有 Fixture 优先留在相邻模块的 `src/test/resources`；
- API Key、Token、生产数据、真实 Host Path、原始 Prompt/Provider 响应和运行生成的数据库、Trace、
  Transcript、Workspace 不得进入本目录；
- 真实模型、外部 MCP、Web Provider 和高成本 E2E 必须保持显式 opt-in，并使用独立测试凭据与预算。

当前只初始化 Testkit 与 Fixture 边界。集中式 Live、E2E 和 Eval 模块应在真实用例开始迁移时再加入，
避免空模块先于可执行行为出现。
