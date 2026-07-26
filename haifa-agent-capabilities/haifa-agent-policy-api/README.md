# Haifa Agent Policy API

Provider-neutral、纯 Java 的 Policy 与 Approval 公共契约。

本模块定义固定字段的 Request/Decision/Rule/Snapshot、受限 Approval Grant、Project Trust、
Approval Target/Authority 引用、产品验证 SPI 和持久化 Port。它不依赖 Runtime、Tool、
Execution、数据库或产品审批模型。

公共决策只有 `ALLOW`、`ASK`、`DENY`。重新认证使用 `ASK + REAUTHENTICATE`；
`BUSINESS_AUTHORIZATION` 只能请求一次性批准，不能形成可复用 Grant。

Runtime 负责 Interaction 生命周期与恢复，上层产品负责组织关系、审批路由、待办、业务状态机
和业务事务。
