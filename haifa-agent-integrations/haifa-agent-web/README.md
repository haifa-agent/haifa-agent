# Haifa Agent Web Tools

`web.search` and `web.fetch` report query/source-specific failures such as unusable provider responses, denied URLs,
or unsupported media as successful structured negative results so a research Agent can refine the query or try
another source without triggering the Runtime's repeated-failure termination guard. Because both Tools are read-only,
provider timeouts and outcome-unknown transport failures are handled the same way; retrying another source cannot
duplicate an external side effect. Authentication, quota, and cancellation failures remain typed invocation failures
and still stop the Run.

When `web.fetch` omits `maxCharacters`, the Tool defaults to 20,000 characters. Callers may request another bounded
size explicitly; the conservative default prevents a handful of fetched pages from exhausting an Agent context.

提供可由不同产品显式装配的 `web.search` 与 `web.fetch` 通用 Tool 能力。

- `io.haifa.agent.web` 保存 Provider-neutral 契约、Provider Registry、URL 安全策略和 Tool Adapter。
- `io.haifa.agent.web.provider` 保存 Aliyun IQS、Brave、Tavily 和 Browserless HTTP Provider。
- 模块不依赖 Runtime、Spring 或任何具体产品；Provider 选择、凭据来源和允许的 Tool alias 由产品装配层冻结。
- `web.search` 的模型可见输入 Schema 根据冻结 Provider 的 capability 精确生成；不支持的可选参数不会暴露给模型，执行前仍保留能力校验作为防御。
- `web.fetch` 在请求交给远端 Provider 前执行 URL 策略检查；返回内容始终按不可信外部内容处理。

当前 Provider：

- Search：Aliyun IQS、Brave、Tavily。
- Fetch：Aliyun IQS、Browserless Content API、Tavily Extract API。

Browserless Fetch 调用 `/content` 获取执行 JavaScript 后的完整 HTML，因此返回格式固定为 `html`，即使
Tool 输入偏好为 Markdown 或纯文本也不会伪造转换结果。API Token 只通过 `Authorization: Bearer ...`
请求头注入，不进入 Endpoint、请求正文或冻结配置；`X-Response-Code` 表示目标页面失败时不会把拦截页
误报为可用来源。默认云端 Endpoint 为 `https://production-sfo.browserless.io/content`，产品可显式冻结
其他 Browserless Cloud 区域或自托管 HTTPS Endpoint。

Tavily Fetch 调用 `/extract`，按 Tool 的格式偏好请求 Markdown 或纯文本，并继续保留最终 URL、内容摘要、
截断状态与不可信外部内容标记。Search 与 Fetch 使用不同的 Credential Definition，但产品可把二者分别
绑定到同一个 `env://TAVILY_API_KEY`；默认 Endpoint 分别为 `https://api.tavily.com/search` 和
`https://api.tavily.com/extract`。

`web.search.country` 使用小写 ISO 3166-1 alpha-2 国家码（例如 `cn`）。各 Provider Adapter 负责转换为
供应商协议；Tavily 会把 `cn` 转换为其要求的英文枚举值 `china`。远端明确返回的 400/422 参数错误会以
`WEB_INVALID_REQUEST` 结构化负结果交给 Agent 修正，不会被误报为结果未知；真实传输中断仍保持
`TOOL_OUTCOME_UNKNOWN` 防重放语义。

验证：

```bash
./mvnw -pl :haifa-agent-web -am test
```
