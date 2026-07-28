# Haifa Agent SQLite Runtime Store

## V5 SDK Conversation

Runtime Migration V5 新增产品中立的 `sdk_conversation` 与 `sdk_conversation_command`：

- `sdk_conversation` 只保存列表、归档和恢复需要的 metadata 及权威 Session/Run 引用，不复制
  Runtime Message 或 Run 正文；
- Tenant/Principal、status、last activity、revision、active dispatch/run 具有固定列、索引和
  CHECK/外键约束；不使用 `ON DELETE CASCADE`，本期也不提供 Session 删除；
- `sdk_conversation_command` 保存 caller scope、operation、idempotency key、canonical request
  digest、dispatch key、结果 Run/revision，用于同 key 去重和跨崩溃窗口恢复；
- `SqliteConversationStore` 实现 SDK Store Port，所有修改使用 revision 条件更新；列表和搜索使用
  稳定 activity/session Cursor，搜索会转义 SQL `LIKE` 通配符；
- `SqliteSdkContributions` 基于同一个 `SqliteStoreFoundation` 显式提供 Runtime Persistence 与
  Conversation 两个 SDK Contribution，SDK 不反向依赖本模块。

## V4 Interaction / Run Input / Runtime Journal

Runtime Migration V4 在不修改 V1～V3 的前提下完成 11 号能力 Task 02：

- `runtime_event` 增加 event schema、correlation/causation；`runtime_event_stream` 保存每个 Run 的
  head/earliest，并在 `BEGIN IMMEDIATE` 事务内分配单调 sequence；
- Event ID 在 append 时一次确定，Outbox 必须引用同一 committed ID；范围查询使用
  `(run_id, sequence)` 索引和排他 Cursor，不加载整 Run；
- `interaction_request/response` 增加 revision、kind、state、expiration outcome、可信 responder、
  canonical digest 和 receipt；部分唯一索引保证每个 Run 最多一个 Pending；
- `run_input` 保存 caller-scoped 幂等键、content codec/hash、expected Run version、
  `ACCEPTED/APPLIED/REJECTED`、Attempt/Iteration 与应用时间；
- `SqliteInteractionPort` 提供条件响应、due/expire/cancel/invalidate 和 applied 恢复；
  `SqliteRunInputPort` 提供 accepted/applied 去重与重启恢复；
- retention 先清理已发布 Outbox，再删除保留下界之前且不再被 Outbox 引用的 Runtime Event；
  未发布消息会阻止删除。

SQLite 仍是 Client Event Page、Interaction、Run Input、Checkpoint 和 Runtime 恢复的唯一持久事实源。
进程内 Subscription 只接收提交后唤醒，并始终返回 SQLite 范围读取。

## V3 Policy / Approval / Security

Runtime Migration V3 追加 `policy_snapshot`、`policy_decision`、`approval_request_metadata`、`approval_response_metadata`、`policy_authorization_evidence`、`approval_grant` 与 `project_trust`。固定查询列、外键、状态 CHECK、版本/hash 交叉校验和条件更新共同构成权威恢复边界；JSONL/Event 仍只是提交后的安全投影，不参与恢复。

`SqliteStoreFoundation` 暴露与 Policy API 对齐的 Store。Project Application 和 CLI 在 SQLite 模式下把同一组实例同时注入 Policy、Runtime Tool 与 Execution，避免进程内 Store 和 SQLite 各持一份授权事实。

本模块提供纯 Java 的 SQLite/MyBatis Runtime Store。当前已完成受控数据库配置、V1～V5 Migration、
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
Schema 的 Application 使用扩展重载，在一次校验中传入包含 Runtime V1～V5 原文的完整 Migration 集合，
并可传入由 Application 自己拥有的静态 `MapperXml`。附加 Mapper 与内建 Mapper 使用相同的
namespace/statement 唯一性、`${}` 禁止和启动期解析校验：

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

V3 提供 Policy/Approval/Trust 权威表。V4 提供稳定 Event Journal range/head/earliest、Interaction
revision/state 和 durable Run Input；旧库通过连续 Migration 升级，重复启动只校验 name/checksum。

## Port—表—Codec 对照

| Runtime 边界 | 表/关键列 | Codec |
| --- | --- | --- |
| `AgentSessionRepository` | `session`; tenant/owner/project/status/version | metadata DTO |
| `RunStateRepository` | `run`; budget/limits/usage/status/version | result/error DTO |
| `ExecutionAttemptRepository` | `execution_attempt`; `(run_id, attempt_number)`, active index | error DTO |
| `RuntimeStateRepository` | message/step/tool_call/plan/output/configuration | ContentPart、Step/Tool、Plan、Configuration DTO |
| `CheckpointRepository` | checkpoint + checkpoint_payload | `RuntimeCheckpointState` DTO |
| Event/Outbox/Idempotency | runtime_event/runtime_event_stream/outbox/outbox_consumer/idempotency | bounded event/result DTO |
| Journal/Interaction/Input | tool_journal + interaction 三表 + run_input | ToolResult、Target、Content Parts DTO |
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
| Tool journal / Interaction / Input | `SqliteToolExecutionJournal`、`SqliteInteractionPort`、`SqliteRunInputPort` |
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
Event ID/范围/head/earliest/索引计划、Outbox/Idempotency、Journal 状态机、Interaction 与 Input
重启恢复/竞争、Continuation 固定密钥恢复/错误密钥/
篡改、Skill/Memory/Asset、busy/locked 分类、权限策略，以及数据库/WAL/SHM 中不出现 Credential、
reasoning、Provider 原文或测试密钥。
