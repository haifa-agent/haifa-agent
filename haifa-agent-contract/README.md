# Haifa Agent Contract

Assistant text deltas are not part of the current durable Run Event Feed contract. The
`AssistantTextDelta` wire payload remains only for backward-compatible decoding of older producers
and stored events; new in-process clients use the Runtime API transient output subscription. A
completed Assistant message is read from the authoritative Session message/Turn representation.

该模块是对外协议层，不是内部领域模型层。当前提供 API 版本、分页协议、标准错误响应，以及
Run、Steer、Runtime Command、Interaction 和 Run Event 的 transport-neutral 纯 Java DTO。

- 允许依赖：`haifa-agent-common` 和 JDK；测试范围内允许测试类库。
- 禁止依赖：`haifa-agent-core`、Runtime、数据库实体、Spring Web DTO 和 Provider SDK DTO。
- Contract 与 Core 的转换必须由 Application 或 Adapter 完成。
- 写请求通过公共幂等键和可选 expected version/revision 表达并发语义；Tenant、Principal、
  Authority 和配置快照必须由可信 Adapter 上下文解析，不能从 DTO Body 接受。
- `InteractionView` 只携带安全展示字段和有界输入约束；未知 Kind/Action/Input 可以作为字符串读取，
  但 Adapter 不得猜测映射或执行。
- Event DTO 的 `eventSchemaVersion` 与 `ApiVersion` 分离。当前模块冻结 Envelope、Cursor、Page
  和 P0 typed payload 形状；Journal 投影、range read、订阅和 HTTP/SSE 映射由相邻模块实现。
- `AUTHENTICATION_REQUIRED` 等错误码是跨 Transport 的稳定机器语义；HTTP 状态和安全 Problem
  Details 由 Transport Adapter 映射。
