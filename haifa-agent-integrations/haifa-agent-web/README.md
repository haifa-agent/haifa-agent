# Haifa Agent Web Tools

提供可由不同产品显式装配的 `web.search` 与 `web.fetch` 通用 Tool 能力。

- `io.haifa.agent.web` 保存 Provider-neutral 契约、Provider Registry、URL 安全策略和 Tool Adapter。
- `io.haifa.agent.web.provider` 保存 Aliyun IQS、Brave 和 Tavily HTTP Provider。
- 模块不依赖 Runtime、Spring 或任何具体产品；Provider 选择、凭据来源和允许的 Tool alias 由产品装配层冻结。
- `web.fetch` 在请求交给远端 Provider 前执行 URL 策略检查；返回内容始终按不可信外部内容处理。

当前 Provider：

- Search：Aliyun IQS、Brave、Tavily。
- Fetch：Aliyun IQS。

验证：

```bash
./mvnw -pl :haifa-agent-web -am test
```
