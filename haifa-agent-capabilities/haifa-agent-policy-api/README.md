# Haifa Agent Policy API

## 09 当前实现

- `PolicyPersistencePorts` 汇总 Snapshot、Decision、Authorization Evidence、Grant 与 Project Trust 的权威持久化 Port。
- `ApprovalGrant` 仅允许 `CAPABILITY_CONFIRMATION`，记录来源 Decision、Request、Response、可信 Responder、到期、消费、撤销原因与乐观锁版本。
- `ProjectTrustExpectation` 由上层产品提供当前项目规范身份、可信根身份、授权配置摘要和 Product Profile；SDK 只做精确比对，不管理组织或审批流程。
- `BUSINESS_AUTHORIZATION` 仍只能形成单笔 `ONCE` 审批证据，不能写入可复用 Grant。

## Request-bound decisions

`PolicyDecision` 可携带固定字段 `PolicyRequest` 与稳定 `requestDigest`，执行边界可以拒绝未知、
占位、主体漂移或请求漂移的 Decision Ref。`PolicyAuthorizationEvidence` 只表示某次 `ASK`
Challenge 已由可信 Verifier/Validator 满足，不扩大 Capability，也不覆盖 `DENY`。

Provider-neutral、纯 Java 的 Policy 与 Approval 公共契约。

本模块定义固定字段的 Request/Decision/Rule/Snapshot、受限 Approval Grant、Project Trust、
Approval Target/Authority 引用、产品验证 SPI 和持久化 Port。它不依赖 Runtime、Tool、
Execution、数据库或产品审批模型。

公共决策只有 `ALLOW`、`ASK`、`DENY`。重新认证使用 `ASK + REAUTHENTICATE`；
`BUSINESS_AUTHORIZATION` 只能请求一次性批准，不能形成可复用 Grant。

Runtime 负责 Interaction 生命周期与恢复，上层产品负责组织关系、审批路由、待办、业务状态机
和业务事务。
