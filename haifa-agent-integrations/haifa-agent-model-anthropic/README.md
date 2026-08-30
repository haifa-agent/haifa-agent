# Haifa Agent Anthropic Model Integration

`haifa-agent-model-anthropic` 是 Haifa Agent 的 Anthropic Messages (`/v1/messages`) 协议独立适配器模块。它负责 Anthropic 协议下的请求构建、Content Block 映射、Named SSE 事件聚合、流式 Token Usage 提取、Thinking Continuation 状态维护以及方言治理。

## 模块定位与架构边界

- **协议专属**：仅承载 Anthropic Messages 协议及厂商特化修饰（Vendor Quirks）；与 `haifa-agent-model-openai-compatible` 及 `haifa-agent-google-gemini` 保持完全正交与独立，无任何相互依赖。
- **纯 Java 边界**：仅依赖 `haifa-agent-model-api` 与 Jackson，不引入供应商第三方 SDK，不依赖 Spring、Runtime Core 或产品层。
- **权威四元组准入**：内部维护不可变的 `AnthropicMessagesBindingRegistry`，仅对已完成测试验证的精确 4 元组 `(providerId, providerModelId, apiStyle, dialect)` 提供 `VERIFIED` 状态。

## 已受治理与验证的方言及模型

| Provider | Provider Model ID | API Style | Dialect | Reasoning 特性 |
| :--- | :--- | :--- | :--- | :--- |
| `deepseek` | `deepseek-v4-flash`, `deepseek-v4-pro` | `anthropic-messages` | `deepseek-anthropic-messages` | `ALWAYS` 强制推理，`HIGH` 级别，需要 continuation |
| `zhipu` | `glm-5.2` | `anthropic-messages` | `zhipu-anthropic-messages` | `ADAPTIVE` 自适应推理，支持 `DISABLED/ENABLED/ADAPTIVE`，`HIGH/MAX` 级别 |

未知模型 ID 或未准入的 4 元组组合一律判定为 `UNVERIFIED` 且不可选择（`selectable() == false`）。

## 主要类清单

- `AnthropicMessagesModel`：实现 `AgentChatModel`，负责 HTTP/SSE 通信、Content Block 组装与流式事件发射；
- `AnthropicMessagesDialects`：管理 `standard`、`deepseek-anthropic-messages`、`zhipu-anthropic-messages` 方言解析与端点安全校验；
- `AnthropicMessagesBindingRegistry`：包私有的 4 元组权威准入注册表，具备重复注册快速失败防护；
- `AnthropicModelProfileFactory`：构建不可变 `ModelBindingProfile` 的权威工厂；
- `AnthropicModelConfiguration`：强类型便利装配 Builder。

## 测试与验证

- 默认所有单测与兼容测试均离线运行（基于本地 Stub / Fake HTTP）；
- `AnthropicMessagesLiveIT` 需显式配置环境变量与 `-Dhaifa.live.anthropic.enabled=true` 才会触发真实调用，默认跳过。
