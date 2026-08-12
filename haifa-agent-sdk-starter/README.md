# Haifa Agent SDK Starter

面向首次接入者的纯 Java 安全默认装配。Starter 默认使用 DeepSeek V4 Flash、环境变量
`DEEPSEEK_API_KEY`、进程内 Runtime Persistence 和 Conversation Store，不启用文件、Shell、Git、MCP、
Web、Memory、Artifact 或 Execution。

可信宿主可以通过 `model(adapter, snapshot)` 注册多个 Provider/模型，并通过 `defaultModel(modelId)`
选择默认模型。自定义目录会替代内置 DeepSeek 目录；Conversation 命令使用已注册 model ID 作为可信
`runProfileId` 选择后续 Run，不能从 Prompt 注入 endpoint 或 Credential。

```java
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.starter.HaifaAgentStarter;

try (HaifaAgent agent = HaifaAgentStarter.builder()
        .name("hello-agent")
        .description("Minimal quickstart assistant")
        .build()) {
    var response = agent.chat("Say hello in one sentence.").await();
    System.out.println(response.text());
}
```

默认 instructions 只是 Quickstart fallback；使用它时 `agent.diagnostics()` 包含
`DEFAULT_INSTRUCTIONS_IN_USE`，显式调用 `instructions(...)` 后该诊断消失。`name`/`description`
仅用于展示和诊断，不进入 Prompt 或选择逻辑。多轮、重试、revision、取消和事件订阅继续使用显式
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
