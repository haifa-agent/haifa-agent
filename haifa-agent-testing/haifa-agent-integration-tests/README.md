# Haifa Agent Integration Tests

确定性的跨模块测试。默认不访问公网、不读取真实 Secret；外部 SaaS 使用 Stub/Fake，本地 SQLite、
文件系统、JSONL 和测试 MCP Server 使用真实实现。

当前模块暂不保存用例：

- Suite Schema、Runner Plan 和 fail-closed 行为属于 Testkit 自身契约，由 Testkit 单元测试覆盖；
- OpenAI-compatible loopback、认证、thinking 和 usage 映射属于 Adapter 组件契约，由 Adapter
  相邻测试覆盖。

后续只有真正跨越多个生产模块、保持确定性且不访问外部 Provider 的用例才进入本模块。测试继续使用
`*IT.java`，由 `-Pci-integration -DskipITs=false` 执行。
