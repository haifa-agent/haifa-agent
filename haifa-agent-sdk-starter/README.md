# Haifa Agent SDK Starter

面向首次接入者的纯 Java 安全默认装配。Starter 默认使用 DeepSeek V4 Flash、环境变量
`DEEPSEEK_API_KEY`、进程内 Runtime Persistence 和 Conversation Store，不启用文件、Shell、Git、MCP、
Web、Memory、Artifact 或 Execution。

可信宿主可以通过 `model(OpenAiCompatibleModelConfiguration)` 减少现有 OpenAI-compatible Integration 的
装配样板，也可以继续通过高级 `model(adapter, snapshot)` 注册模型；两种入口都支持多个 Provider/模型，
并通过 `defaultModel(modelId)` 选择默认模型。自定义目录会替代内置 DeepSeek 目录；Conversation 命令
使用已注册 model ID 作为可信 `runProfileId` 选择后续 Run，不能从 Prompt 注入 endpoint 或 Credential。

类型化配置生成同一 `ResolvedModelSnapshot` 和精确 Adapter coordinate；模型调用选项进入 Snapshot digest，
请求超时进入 Starter 的冻结 Run Profile。它不提供发现、fallback、健康路由或动态 Catalog。百炼/方舟
仍使用各自受治理工厂，原二参数入口继续服务完全自定义 Adapter。

有 `DEEPSEEK_API_KEY` 时，第一个 Agent 只需要一次构建、一次调用：

```java
import io.haifa.agent.starter.HaifaAgentStarter;

try (var haifa = HaifaAgentStarter.create()) {
    System.out.println(haifa.chat("Hello, Java!").await().text());
}
```

默认 name、instructions、模型和进程内 Store 都由 Starter 提供；需要改变行为时再显式配置，不让
Hello World 承担生产装配概念。

结构化最终结果使用同一个 Starter 和 Runtime 路径：

```java
public record TripPlan(String city, int days, List<String> activities) {}

var response = agent.chat("Plan a two-day trip.", TripPlan.class).await();
TripPlan plan = response.value();
```

注册模型必须声明 `ModelCapability.STRUCTURED_OUTPUT`，并由其 Adapter 实现对应协议映射。Runtime 在最终
回答上校验冻结的 record Schema，持久化成功后 SDK 才解码；Tool Loop 仍可先返回 Tool Call，不提供
类型化 partial stream。当前 API 未声明 Stable。

默认 instructions 只是 Quickstart fallback；使用它时 `agent.diagnostics()` 包含
`DEFAULT_INSTRUCTIONS_IN_USE`，显式调用 `instructions(...)` 后该诊断消失。`name` 仅用于展示和
Conversation display name，不进入 Prompt 或选择逻辑；Agent `description` 暂不暴露。多轮、重试、
revision、取消和事件订阅继续使用显式
Conversation/Run API。

运行前设置 `DEEPSEEK_API_KEY`。该入口会访问真实 DeepSeek API 并产生费用。Starter 的进程内状态在
进程退出后丢失；生产系统应通过 `haifa-agent-sdk` 显式装配持久化、可信 Caller、Policy、Credential
和所需 Capability。

验证：

```bash
./mvnw -pl :haifa-agent-sdk-starter -am test
./mvnw -pl :haifa-agent-sdk-starter -am -Prelease verify
```

真实模型测试只有同时设置 `HAIFA_DEEPSEEK_LIVE_TEST=true` 与 `DEEPSEEK_API_KEY` 才运行。
