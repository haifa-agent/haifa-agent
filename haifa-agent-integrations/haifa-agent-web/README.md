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
- `io.haifa.agent.web.provider` 保存 Aliyun IQS、Brave 和 Tavily HTTP Provider。
- 模块不依赖 Runtime、Spring 或任何具体产品；Provider 选择、凭据来源和允许的 Tool alias 由产品装配层冻结。
- `web.search` 的模型可见输入 Schema 根据冻结 Provider 的 capability 精确生成；不支持的可选参数不会暴露给模型，执行前仍保留能力校验作为防御。
- `web.fetch` 在请求交给远端 Provider 前执行 URL 策略检查；返回内容始终按不可信外部内容处理。

当前 Provider：

- Search：Aliyun IQS、Brave、Tavily。
- Fetch：Aliyun IQS。

验证：

```bash
./mvnw -pl :haifa-agent-web -am test
```
