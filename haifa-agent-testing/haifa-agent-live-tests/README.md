# Haifa Agent Live Tests

真实外部 Provider 的窄范围连通性与协议兼容测试。普通构建和 `ci-fast` 跳过；Suite Runner 只有在
显式 `--execute`、Secret 和预算均满足时才启用。

当前 `PrimaryModelLiveIT` 对应 `CP-01`，验证真实 DeepSeek 请求、响应和 usage。MCP 的 `CP-09`
暂时复用 Integration 模块相邻的 `UtilityMcpCompatibilityLiveIT`；后续迁移时保持相同 caseId。
