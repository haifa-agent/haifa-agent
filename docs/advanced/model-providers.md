# Model Providers

Haifa Agent 将 Provider-neutral Model Contract 与具体 Provider Protocol Adapter 分离。

Run 会冻结自己使用的 Model Snapshot 与 Adapter coordinate。Runtime 不会把已经创建的 Run 静默迁移到不同 Provider / Model Binding。

## Starter 内建默认值

安全默认 SDK Starter 当前会构建一个 DeepSeek V4 Flash Binding：

- Endpoint：https://api.deepseek.com
- Credential：env://DEEPSEEK_API_KEY
- 内建 Snapshot Thinking：disabled
- Persistence：process-local

这些只是 Starter 默认值，不是整个 Model Integration 的全局限制。

## 显式注册 Model

可信应用代码可以注册：

- Typed OpenAI-compatible Model Configuration；
- 或显式 AgentChatModel + ResolvedModelSnapshot。

一旦注册自定义 Model，就会替代 Starter 内建 Model Catalog。

可以注册多个 Model ID，并通过 defaultModel(...) 选择默认项。

## Provider Integrations

仓库当前包含 OpenAI-compatible、Anthropic-style、Google Gemini 以及 Local Auth Compatibility 等 Integration。

OpenAI-compatible 模块支持多个经过审查的 Provider Dialect / Binding。Provider / Model 兼容变化通常比公共 Architecture 更快，因此精确事实以模块 README 与测试为准：

- [OpenAI-compatible Integration](../../haifa-agent-integrations/haifa-agent-model-openai-compatible/README.md)
- [Anthropic Integration](../../haifa-agent-integrations/haifa-agent-model-anthropic/README.md)
- [Google Gemini Integration](../../haifa-agent-integrations/haifa-agent-google-gemini/README.md)

## 不做隐式 Fallback

Haifa Agent 不把 Provider Catalog 当成 best-effort router。

未知、不可用或不匹配的 Binding 会显式失败；Authentication failure 也不会自动切到另一个 Model。

## Reasoning / Continuation

Reasoning 能力取决于具体 Provider 与 Binding。

某些 Provider 为保持 Tool-call Protocol 正确性，需要保存受保护的 Provider Continuation；这类数据不是公开 Reasoning Output。

面向产品的应用应依赖归一化 Final Answer、Tool Call、Usage 与安全 Runtime Lifecycle Event，而不是依赖 Provider 私有 Chain-of-Thought。

## Credentials

Model Credential 通过间接 Reference 表达，并在 Adapter Boundary 解析。

不要把 Secret 值写入 ProductProfile、Run Input、Prompt、Log 或 Public Diagnostics。
