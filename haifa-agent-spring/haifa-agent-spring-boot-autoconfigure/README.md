# Haifa Agent Spring Boot Auto-configuration

为 Spring Boot 应用提供薄自动装配。满足以下条件时创建一个由容器托管的 `HaifaAgent`：

- classpath 中存在纯 Java SDK 与 Starter；
- `haifa.agent.enabled` 未配置或为 `true`；
- 应用没有自行声明 `HaifaAgent` Bean。

自动装配会按 Spring 的排序规则收集全部 `JavaTool<?, ?>` Bean，并在构建 Agent 时注册。应用声明的
单个 Tool 因而无需理解 Catalog、binding、Invoker 或 Product Profile。容器关闭时会调用
`HaifaAgent.close()`。

公开配置：

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| `haifa.agent.enabled` | `true` | 是否启用默认 Agent 自动装配 |
| `haifa.agent.name` | `haifa-agent` | 展示及 Conversation display name |
| `haifa.agent.instructions` | Starter 默认值 | 可信系统指令 |
| `haifa.agent.model.credential-environment-variable` | `DEEPSEEK_API_KEY` | 凭据环境变量名，不是凭据值 |
| `haifa.agent.model.connect-timeout` | `10s` | 模型 HTTP 连接超时 |

不提供明文 `api-key`、endpoint、model ID 或 Thinking 开关。默认模型固定为 DeepSeek V4 Flash 且
Thinking disabled。高级应用可声明自己的 `HaifaAgent` 或 `SdkCallerProvider` Bean；生产凭据接入由
应用拥有的 `HaifaAgent` 装配负责。

`name`、`instructions` 和有序 `HaifaAgentStarterCustomizer` 都映射到同一个纯 Java
Starter Builder；自动装配没有复制 Chat、Prompt 或 Runtime 语义，也没有新增 Store-specific Starter。
