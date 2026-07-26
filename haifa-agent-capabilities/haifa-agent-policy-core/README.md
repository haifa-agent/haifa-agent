# Haifa Agent Policy Core

Policy API 的纯 Java 默认实现。

本模块提供确定性规则匹配、`DENY > ASK > ALLOW` 合并、Grant 精确匹配、本地能力确认验证和
内存 Store。它不拥有 Run、Interaction、Tool、Execution 或产品业务流程，也不依赖数据库、
Spring、Jackson 或 Provider SDK。

没有匹配规则且没有显式默认规则、验证 Provider 缺失、目标漂移、Grant/Trust 失效时均
fail closed。
