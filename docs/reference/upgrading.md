# 升级

Haifa Agent 仍处于 Pre-1.0。

因此升级时应把它看成一次 Code + Data Compatibility Event，而不是假设所有 Internal / Public Surface 都已经获得完整 Semantic Versioning 稳定承诺。

## 升级之前

1. 阅读 [CHANGELOG.md](../../CHANGELOG.md)。
2. 检查 BOM / Starter Version 变化。
3. 如果使用非默认 Provider，检查 Provider Binding 变化。
4. 按产品运维流程备份 Durable SQLite / Application Data。
5. 用新版本运行自己的 Product Test。

## 0.1.1 升级到 0.1.2

0.1.2 的 Memory API 改为直接 CRUD，移除了 Candidate / Review 与旧 Memory 类型。
SDK 使用方需要按当前 `AgentMemories` API 修改并重新编译；旧版本二进制兼容不受保证。

先停止应用，再备份完整数据目录，包括 SQLite 数据库、可能存在的 `-wal` / `-shm` 文件、
Artifact 目录与产品配置。先在备份副本上验证升级，保留原版本程序及原始备份用于回退。
数据库升级后不要直接用 0.1.1 程序打开；回退时恢复完整原始备份。

SQLite V15 只在旧 Memory 表没有 ACTIVE Memory 和 PENDING Candidate 时迁移。
存在这些数据时会拒绝启动，保留数据，不会自动丢弃。需要保留内容的用户应在旧版本中导出所需内容，
保留原库，再使用新的独立数据目录启动 0.1.2，并通过新 Memory API 重新录入。
当前没有自动迁移这些旧 Memory 数据的工具；不要修改迁移历史或删除记录来绕过保护。

部分旧开发数据库还跨越了 Runtime payload / checkpoint 与 V13 Conversation 的 clean cutover。
这类数据库需要在保留备份后重建，不能承诺原地升级。Intentional Pause / Approval 的恢复测试
证明当前格式的重开行为，不代表所有历史开发格式都可读取。详见
[SQLite Store](../../haifa-agent-integrations/haifa-agent-store-sqlite/README.md)。

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
