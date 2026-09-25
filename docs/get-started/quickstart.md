# 快速开始

体验 Haifa Agent 最短的路径是 Pure Java Starter。

当前 Starter 默认使用 DeepSeek V4 Flash，从 DEEPSEEK_API_KEY 读取凭据，内建默认 Model Snapshot 关闭 Thinking，并且只在当前进程内保存 Runtime 与 Conversation 状态。它不会隐式启用文件、Shell、Git、MCP、Web、Memory、Artifact 或 Execution。

真实 Model 调用可能产生 Provider 费用。

## 1. 配置 Credential

macOS / Linux：

~~~bash
export DEEPSEEK_API_KEY="<your-api-key>"
~~~

Windows PowerShell：

~~~powershell
$env:DEEPSEEK_API_KEY = '<your-api-key>'
~~~

不要把 API Key 写进源码、Prompt、提交到 Git 的 YAML 或日志。

## 2. 创建 Agent

~~~java
import io.haifa.agent.starter.HaifaAgentStarter;

public final class HelloHaifa {
    public static void main(String[] args) throws Exception {
        try (var agent = HaifaAgentStarter.create()) {
            System.out.println(
                    agent.chat("Introduce Haifa Agent in one sentence.")
                            .await()
                            .text());
        }
    }
}
~~~

HaifaAgent 持有 Runtime 资源，因此应由宿主应用负责关闭。

## 3. 运行仓库中的示例

仓库已经包含与上面相同路径的可执行示例。

macOS / Linux：

~~~bash
./mvnw -pl :haifa-agent-sdk-example -am compile org.codehaus.mojo:exec-maven-plugin:3.5.1:java -Dexec.mainClass=io.haifa.example.sdk.basic.HelloHaifa
~~~

Windows PowerShell：

~~~powershell
.\mvnw.cmd -pl :haifa-agent-sdk-example -am compile org.codehaus.mojo:exec-maven-plugin:3.5.1:java '-Dexec.mainClass=io.haifa.example.sdk.basic.HelloHaifa'
~~~

## Starter 默认提供什么

默认 Starter 的目标是降低第一次使用门槛：

- 一个进程内 HaifaAgent；
- 内建 DeepSeek V4 Flash Model；
- 基于环境变量的 Credential 解析；
- 进程内 Runtime 与 Conversation Persistence；
- standard approval Policy preset；
- 默认 Agent name 与 fallback instructions。

进程退出后，这些进程内状态会丢失。生产应用应显式选择 Persistence、Identity、Credential、Policy 与所需 Capabilities，而不是把 Starter 默认值直接当成生产配置。

## 下一步

- [配置](configuration.md)
- [核心概念](key-concepts.md)
- [Java Tools](../advanced/tools.md)
- [Structured Output](../advanced/structured-output.md)
