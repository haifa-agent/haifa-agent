# Haifa Agent Contract

该模块是对外协议层，不是内部领域模型层。当前提供 API 版本、分页协议、标准错误响应，以及
Run、Steer、Runtime Command、Interaction 和 Run Event 的 transport-neutral 纯 Java DTO。

- 允许依赖：`haifa-agent-common` 和 JDK；测试范围内允许测试类库。
- 禁止依赖：`haifa-agent-core`、Runtime、数据库实体、Spring Web DTO 和 Provider SDK DTO。
- Contract 与 Core 的转换必须由 Application 或 Adapter 完成。
- 写请求通过公共幂等键和可选 expected version/revision 表达并发语义；Tenant、Principal、
  Authority 和配置快照必须由可信 Adapter 上下文解析，不能从 DTO Body 接受。
- `InteractionView` 只携带安全展示字段和有界输入约束；未知 Kind/Action/Input 可以作为字符串读取，
  但 Adapter 不得猜测映射或执行。
- Event DTO 的 `eventSchemaVersion` 与 `ApiVersion` 分离。当前模块只冻结 Envelope、Cursor、Page
  和 P0 typed payload 形状；Journal 投影、range read 与订阅由后续任务实现。
