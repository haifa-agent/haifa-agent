# Haifa Agent Policy Core

## 09 当前实现

`DefaultApprovalGrantService` 对 `ONCE / SESSION / PROJECT` Grant 做逐字段匹配；`ONCE` 通过 Store 条件更新原子消费，`PROJECT` 还必须匹配有效的 `ProjectTrust` 与调用方提供的当前 `ProjectTrustExpectation`。并发消费、撤销、过期、主体或配置漂移均 fail closed。

企业产品只通过 `ApprovalAuthorityVerifier`、`ApprovalTargetValidator` 和稳定引用接入。组织关系、审批节点、路由、待办、审批意见正文及业务事务不属于本模块。

除规则匹配、Grant/Trust 内存 Store 外，本模块提供 challenge-satisfaction evidence 的内存 Store。
它仍不拥有 Runtime Interaction 或产品审批流程。

Policy API 的纯 Java 默认实现。

本模块提供确定性规则匹配、`DENY > ASK > ALLOW` 合并、Grant 精确匹配、本地能力确认验证和
内存 Store。它不拥有 Run、Interaction、Tool、Execution 或产品业务流程，也不依赖数据库、
Spring、Jackson 或 Provider SDK。

没有匹配规则且没有显式默认规则、验证 Provider 缺失、目标漂移、Grant/Trust 失效时均
fail closed。
