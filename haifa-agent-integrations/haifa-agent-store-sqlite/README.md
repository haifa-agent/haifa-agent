# Haifa Agent SQLite Runtime Store

本模块提供纯 Java 的 SQLite/MyBatis Runtime Store。当前已完成受控数据库配置、V1/V2 Migration、
版本化 Codec、线程绑定 UoW，以及 `RuntimePersistencePorts` 所需的全部 SQLite 业务适配器。
Project Application/CLI 已可显式选择本模块；Runtime 的进程重启恢复由注入的 Port 与每次启动唯一
worker ID 驱动。

## 边界

- SQLite 是后续 Runtime 恢复的唯一权威事实源；本模块不提供 JSONL-only 模式。
- Runtime Core 只暴露 Port。本模块作为 Integration 依赖 `runtime-core`，反向依赖被架构测试禁止。
- 只使用 MyBatis Core 和 Xerial SQLite JDBC；不使用 Spring、连接池、ORM、自动生成器或动态 SQL。
- 数据库路径由调用方提供，必须是父目录已存在且可写的绝对文件路径。
- 初始化会把数据库目录设为 POSIX `0700` 或 Windows 当前用户独占 ACL，并对主文件、WAL、SHM
  和 rollback journal 应用文件 `0600`/等价 ACL；无法复核时分类为 `FILE_PERMISSION_FAILED`。
- 默认不开启 SQL 日志；Mapper XML 禁止 `${}`，所有值只能使用 `#{}`。
- `SqliteStoreFoundation.persistencePorts(protector)` 组合完整持久化端口；Model Continuation 必须注入可跨实例恢复的 protector。

## 初始化与所有权

调用方通过 `SqliteStoreFoundation.initialize(configuration, clock)` 完成纯 Runtime 初始化；拥有额外
Schema 的 Application 使用三参数重载，在一次校验中传入包含 Runtime V1/V2 原文的完整 Migration 集合：

1. 打开受控 JDBC Connection，设置并验证 WAL；
2. 在每个 Connection 上设置并验证 `foreign_keys=ON` 与 `busy_timeout`；
3. 校验已执行 Migration 的 name/checksum，并在 `BEGIN IMMEDIATE` 中原子执行待应用版本；
4. 程序化创建并启动期校验 MyBatis Configuration；
5. 创建 `SqliteRuntimeUnitOfWork` 与显式 Payload Codec 注册入口。

UoW 始终保持 JDBC `autoCommit=true`，在同一 Connection 上执行 SQL 级
`BEGIN IMMEDIATE`、`COMMIT`、`ROLLBACK`。外层 UoW 独占 Connection 和 SqlSession；同线程嵌套调用复用
外层上下文，任何嵌套失败都会把外层标记为 rollback-only。MyBatis 使用 `MANAGED` 且
`closeConnection=false`，不会提交、回滚或关闭 UoW Connection。

`BEGIN IMMEDIATE` 在事务工作执行前遇到 SQLite `BUSY/LOCKED` 时分类为 `DATABASE_BUSY`。这只是供
Application 选择安全、有界重试的精确信号；事务工作开始后的 SQL、提交不确定性或其他数据库错误不会被
归入该类别。

## Schema 与 Migration

V1 一次性创建 26 张逻辑表：

表名直接使用领域语义名称，不使用 `haifa_agent_` 或其他产品前缀；Migration 元数据表固定命名为
`schema_migration`。

| 状态组 | 表 |
| --- | --- |
| Migration | `schema_migration` |
| Session/Run | `session`, `configuration_snapshot`, `run`, `execution_attempt` |
| Message/Loop | `session_message`, `step`, `tool_call`, `plan`, `run_output` |
| Checkpoint | `checkpoint`, `checkpoint_payload` |
| Event/Delivery | `runtime_event`, `outbox`, `outbox_consumer`, `idempotency` |
| Extended state | `memory_selection`, `model_continuation`, `skill_activation`, `skill_resource_usage`, `conversation_summary`, `tool_result_asset` |
| Tool/Interaction | `tool_journal`, `interaction_request`, `interaction_response`, `interaction_application` |

固定标识、状态、序号、时间、乐观锁版本与常用查询字段使用独立列。复杂结构使用显式注册的有界 BLOB；
每个 BLOB 都有相邻 `*_schema_version` 与 `*_hash`。时间统一保存为 UTC epoch milliseconds。V1 同时定义
外键、Run 内序号唯一约束、Session Message 序号、Attempt 编号、Interaction/Idempotency 去重，以及
同一 Run 最多一个 `QUEUED`/`RUNNING` Attempt 的部分唯一索引。

V2 只补充无损恢复所需字段：Run 的 waiting request/termination description，以及 Configuration 与
Checkpoint payload 自身的完整性 hash。Migration 仍按 checksum 严格校验并在 `BEGIN IMMEDIATE` 中执行。

## Port—表—Codec 对照

| Runtime 边界 | 表/关键列 | Codec |
| --- | --- | --- |
| `AgentSessionRepository` | `session`; tenant/owner/project/status/version | metadata DTO |
| `RunStateRepository` | `run`; budget/limits/usage/status/version | result/error DTO |
| `ExecutionAttemptRepository` | `execution_attempt`; `(run_id, attempt_number)`, active index | error DTO |
| `RuntimeStateRepository` | message/step/tool_call/plan/output/configuration | ContentPart、Step/Tool、Plan、Configuration DTO |
| `CheckpointRepository` | checkpoint + checkpoint_payload | `RuntimeCheckpointState` DTO |
| Event/Outbox/Idempotency | runtime_event/outbox/outbox_consumer/idempotency | bounded event/result DTO |
| Journal/Interaction | tool_journal + interaction 三表 | ToolResult、Target、inputs DTO |
| Summary/Memory/Continuation/Skill/Asset | extended-state 六表 | 对应显式 DTO；Continuation 只保存 protector 输出 |

Task 01 快照中的标量字段均有固定列；嵌套值只落入上表列出的版本化 Payload。所有 Row DTO 与业务
Codec 都使用受控重建入口，不把领域对象直接交给 MyBatis。

## Codec 与 MyBatis

- `VersionedPayloadCodecRegistry` 只解码显式注册的 `PayloadType<T>`，关闭 Jackson default typing；
- 未知 type/version/field、类型不匹配、超限、hash 不匹配均分类 fail closed；
- JSON 使用稳定属性/Map 顺序生成字节，再计算 SHA-256；
- `InstantEpochMillisTypeHandler` 与 `BoundedBlobTypeHandler` 是基础 TypeHandler；
- ID 和 Enum 通过显式 `StringIdentifierCodec`、`StableEnumCodec` 转换，未知值不回退；
- Mapper 使用显式 constructor/resultMap 与 `#{}` 参数；禁止 `${}` 和任意类名反序列化。

## Port 覆盖矩阵

| Port | SQLite 实现 |
| --- | --- |
| Session / Run / Attempt | `SqliteAgentSessionRepository`、`SqliteRunStateRepository`、`SqliteExecutionAttemptRepository` |
| Runtime state / Message / Configuration | `SqliteRuntimeStateRepository`、`SqliteSessionMessageRepository` |
| Checkpoint | `SqliteCheckpointRepository` |
| Event / Outbox / Idempotency | `SqliteRuntimeEventAppender`、`SqliteRuntimeOutboxPublisher`、`SqliteIdempotencyRepository` |
| Tool journal / Interaction | `SqliteToolExecutionJournal`、`SqliteInteractionPort` |
| Summary / Memory / Continuation / Skill / Asset | 对应 `Sqlite*Repository` / `SqliteToolResultAssetStore` |
| Atomic composition | `SqliteRuntimeUnitOfWork`、`SqliteStoreFoundation.persistencePorts(...)` |

Project Application/CLI 已实现显式 `MEMORY`、`SQLITE`、`SQLITE_WITH_JSONL` 选择，并在启动时注入
Runtime Port、唯一 worker ID 与安全 busy retry。仍未接入的边界包括常驻 Outbox 后台投递器，以及生产
环境 KMS/Vault 密钥解析与轮换；当前 CLI 只解析稳定 `env://` secret reference。

## 验证

```powershell
.\mvnw.cmd -pl :haifa-agent-store-sqlite -am test
```

测试覆盖首次建库、重复启动、checksum 漂移、Migration 故障回滚、完整 Schema、Codec fail-closed、
全部主要 Port 的文件重开 round-trip、乐观锁、活动 Attempt、消息 cursor/脱敏、Checkpoint 完整性、
Event sequence、Outbox/Idempotency、Journal 状态机、Interaction 竞争、Continuation 固定密钥恢复/错误密钥/
篡改、Skill/Memory/Asset、busy/locked 分类、权限策略，以及数据库/WAL/SHM 中不出现 Credential、
reasoning、Provider 原文或测试密钥。
