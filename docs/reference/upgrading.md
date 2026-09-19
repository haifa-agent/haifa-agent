# 升级

Haifa Agent 仍处于 Pre-1.0。

因此升级时应把它看成一次 Code + Data Compatibility Event，而不是假设所有 Internal / Public Surface 都已经获得完整 Semantic Versioning 稳定承诺。

## 升级之前

1. 阅读 [CHANGELOG.md](../../CHANGELOG.md)。
2. 检查 BOM / Starter Version 变化。
3. 如果使用非默认 Provider，检查 Provider Binding 变化。
4. 按产品运维流程备份 Durable SQLite / Application Data。
5. 用新版本运行自己的 Product Test。

## Frozen Run 与 Persisted Data

不要假设新 Binary 一定可以解释所有历史实验 Payload。

在 1.0 之前，项目会主动删除部分无用 / Superseded Compatibility Layer，而不是为每一代内部 Prototype 永久保留 Reader。

如果 Release Note 明确要求重建开发数据，应按要求重建，而不是绕过 Migration / Codec Check。

## Provider Change

Provider Capability / Dialect 属于显式 Frozen Binding。

升级后应重新核对：

- Endpoint；
- Credential Reference；
- Provider / Model ID；
- API Style / Dialect；
- Capability Set；
- Reasoning / Structured Output Behavior。

## Application Products

Coding Agent 与 Personal Assistant 除共享 Runtime State 外，也可能拥有 Product-owned Persistence。

有产品专用 Migration / Backup 说明时，应遵循产品说明。

## Verification

构建并测试你真正准备部署的同一个 Revision。

不要验证一个 Commit，却发布另一个 Commit。
