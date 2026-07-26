# Haifa Agent Integration Tests

确定性的跨模块测试。默认不访问公网、不读取真实 Secret；外部 SaaS 使用 Stub/Fake，本地 SQLite、
文件系统、JSONL 和测试 MCP Server 使用真实实现。

当前测试包括：

- `SuitePlanningIntegrationIT`：验证公共 Critical Path Catalog、私有 Suite Schema 和 Runner
  计划阶段能够协作，并且未知 Case、缺失 Matrix 或不安全运行根都会 fail closed；
- `OpenAiCompatibleStubIntegrationIT`：用 loopback Stub Server 和共享安全 Fixture 驱动真实模型
  Adapter，验证协议映射、usage、thinking 关闭和认证头。

后续产品 Integration 用例继续使用 `*IT.java`，由 `-Pci-integration -DskipITs=false` 执行。
