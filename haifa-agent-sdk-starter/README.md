# Haifa Agent SDK Starter

面向首次接入者的纯 Java 安全默认装配。Starter 默认使用 DeepSeek V4 Flash、环境变量
`DEEPSEEK_API_KEY`、进程内 Runtime Persistence 和 Conversation Store，不启用文件、Shell、Git、MCP、
Web、Memory、Artifact 或 Execution。

```java
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.conversation.ConversationRecord;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.starter.HaifaAgentStarter;

try (HaifaAgent agent = HaifaAgentStarter.create()) {
    ConversationRecord conversation = agent.conversations()
            .start(new StartConversationCommand("hello-1", "Hello Haifa", "Say hello in one sentence."));
    AgentRunSnapshot completed = agent.runs().await(conversation.activeRunId().orElseThrow());
    System.out.println(completed.output().orElseThrow());
}
```

运行前设置 `DEEPSEEK_API_KEY`。该入口会访问真实 DeepSeek API 并产生费用。Starter 的进程内状态在
进程退出后丢失；生产系统应通过 `haifa-agent-sdk` 显式装配持久化、可信 Caller、Policy、Credential
和所需 Capability。

验证：

```bash
./mvnw -pl :haifa-agent-sdk-starter -am test
./mvnw -pl :haifa-agent-sdk-starter -am -Prelease verify
```

真实模型测试只有同时设置 `HAIFA_DEEPSEEK_LIVE_TEST=true` 与 `DEEPSEEK_API_KEY` 才运行。
