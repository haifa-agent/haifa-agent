# Haifa Agent Contract

该模块是对外协议层，不是内部领域模型层。当前提供 API 版本、分页协议和标准错误响应，后续 REST、SSE、Webhook 与 Approval DTO 在此演进。

- 允许依赖：`haifa-agent-common` 和 JDK；测试范围内允许测试类库。
- 禁止依赖：`haifa-agent-core`、Runtime、数据库实体、Spring Web DTO 和 Provider SDK DTO。
- Contract 与 Core 的转换必须由 Application 或 Adapter 完成。
