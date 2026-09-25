# Haifa Agent

[![Feature PR Fast](https://github.com/haifa-agent/haifa-agent/actions/workflows/feature-pr-fast.yml/badge.svg?branch=dev)](https://github.com/haifa-agent/haifa-agent/actions/workflows/feature-pr-fast.yml)
![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![Version](https://img.shields.io/badge/version-0.1.2--SNAPSHOT-blue)

**Java / Spring 生态的 Agent Runtime、SDK 与本地 Agent 应用。**

用 Pure Java 构建可以调用 Tool、进行多轮 Conversation、支持 HITL、持久化与长任务执行的 AI Agent。

你可以直接使用 Haifa Agent 自带的 **Coding Agent** 和 **Personal Assistant**，也可以通过 **Java SDK** 把 Agent 能力嵌入现有 Java / Spring Boot 应用。

> 当前版本：`0.1.2-SNAPSHOT`
>
> 项目仍处于快速演进阶段，README 和 `docs/` 只描述当前源码已经实现的能力。

---

## 三种使用 Haifa Agent 的方式

### Coding Agent

面向本地软件开发的 Terminal Agent：读取和修改代码、执行 Shell / Build / Test、使用 `git` / `gh`，并通过持续 Conversation 完成长任务。

```text
┌──────────────────────────────────────────────────────────────────────┐
│ Haifa Coding Agent                                      GPT-5.6 Sol │
├──────────────────────────────────────────────────────────────────────┤
│ > Fix the failing tests in haifa-agent-runtime-core                 │
│                                                                      │
│ Searched for RuntimeCoreTest                                        │
│ Viewed RuntimeCore.java:120-260                                     │
│ Ran: ./mvnw -pl :haifa-agent-runtime-core -am test                  │
│                                                                      │
│ ✦ Found the failure in checkpoint recovery                          │
│ ✦ Updated 2 files                                                   │
│ ✦ Tests: 48 passed                                                  │
│                                                                      │
├──────────────────────────────────────────────────────────────────────┤
│ > _                                                                  │
└──────────────────────────────────────────────────────────────────────┘
```

支持：

- File read / write / patch
- Shell / Build / Test
- `git` / `gh`
- Multi-turn Coding Session
- Workspace authorization
- Model selection
- HITL Approval
- Session persistence / resume

[查看 Coding Agent →](docs/applications/coding-agent.md)

---

### Personal Assistant

本地 Web Assistant，在同一个 Runtime 上组合 Conversation、Tool、Skill、Memory、Mission 与 Deep Research。

```text
┌──────────────────────────────────────────────────────────────────────┐
│ Haifa Personal Assistant                                  DeepSeek  │
├───────────────────────┬──────────────────────────────────────────────┤
│ Conversations         │ 帮我调研最近两周 Java Agent 生态的发展     │
│                       │                                              │
│ ▸ Agent Runtime       │ Research Plan                                │
│ ▸ AI Infrastructure  │   ✓ 搜索资料                                 │
│ ▸ Weekly Research     │   ✓ 阅读来源                                 │
│                       │   ◉ 综合报告                                 │
│                       │                                              │
│ Missions              │ Sources 12 · Tool Calls 28                   │
│ ▸ Java Agent Research │                                              │
│                       │ Java Agent 生态目前主要沿着……                │
├───────────────────────┴──────────────────────────────────────────────┤
│ Ask anything...                                                     │
└──────────────────────────────────────────────────────────────────────┘
```

支持：

- Multi-turn Conversation
- 多 Model / Provider
- Tool / Skill / MCP
- Memory
- Mission
- Deep Research
- Image / Audio input
- Local Web UI
- Durable SQLite state

[查看 Personal Assistant →](docs/applications/personal-assistant.md)

---

### Java SDK

把 Agent 直接嵌入你的 Java 应用。

```java
import io.haifa.agent.starter.HaifaAgentStarter;

public class HelloHaifa {
    public static void main(String[] args) throws Exception {
        try (var agent = HaifaAgentStarter.create()) {
            var response = agent
                    .chat("用一句话介绍 Haifa Agent")
                    .await();

            System.out.println(response.text());
        }
    }
}
```

输出示例：

```text
Haifa Agent 是一个面向 Java 与 Spring 生态的 Agent Runtime 与开发框架。
```

Starter 默认使用 DeepSeek V4 Flash。设置：

```bash
export DEEPSEEK_API_KEY="<your-api-key>"
```

即可运行第一个真实 Model 请求。

Tool、MCP、Execution、Memory 等能力不会被默认全部打开，而是根据产品需要显式装配。

[SDK Quickstart →](docs/get-started/quickstart.md)

---

## 快速开始

### 方式一：运行 Coding Agent

#### macOS / Linux

构建本地发行目录：

```bash
./scripts/package-local-coding-agent.sh
export PATH="$HOME/.haifa-agent/coding:$PATH"
```

进入任意项目：

```bash
cd /path/to/your/project
haifa-coding
```

#### Windows

```powershell
.\scripts\package-local-coding-agent.ps1
$env:Path = "$env:USERPROFILE\.haifa-agent\coding;$env:Path"
```

然后：

```powershell
Set-Location D:\path\to\your\project
haifa-coding
```

首次使用时根据 Terminal 引导配置 Model Credential。

Coding Agent 默认使用当前目录作为 Workspace。

### 方式二：嵌入 Java 应用

Haifa Agent 当前要求：

- Java 21
- Maven 3.9+，或直接使用仓库自带 Maven Wrapper

Pure Java 项目：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.haifa</groupId>
            <artifactId>haifa-agent-bom</artifactId>
            <version>0.1.2-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.haifa</groupId>
        <artifactId>haifa-agent-sdk-starter</artifactId>
    </dependency>
</dependencies>
```

当前 `0.1.2-SNAPSHOT` 尚不是正式公共 Release，源码开发阶段可以先从本仓库安装到本地 Maven Repository。

[Installation →](docs/get-started/installation.md)

---

## 为什么使用 Haifa Agent

### Pure Java Core，Spring 原生友好

Core、Runtime、SDK、Tool、Memory 等核心能力保持 Pure Java。

Spring Boot 只作为 Adapter / Starter 层存在，负责 Bean、Configuration 与 Lifecycle：

```text
Your Spring Application
        │
        ▼
Spring Boot Starter
        │
        ▼
Haifa Agent SDK
        │
        ▼
Agent Runtime
```

运行 Agent 不需要额外启动一套 Python Agent Service。

### 用 Java record 定义 Tool

不需要手写一堆 JSON Schema 和参数解析代码。

```java
public record WeatherRequest(String city) {}

public record WeatherResponse(String forecast) {}

public final class WeatherTool
        implements JavaTool<WeatherRequest, WeatherResponse> {

    @Override
    public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
        return JavaToolSpec.builder(
                        "weather_get",
                        WeatherRequest.class,
                        WeatherResponse.class)
                .description("Get weather for a city")
                .pure()
                .build();
    }

    @Override
    public WeatherResponse invoke(
            WeatherRequest request,
            JavaToolContext context) {
        return new WeatherResponse("Sunny");
    }
}
```

SDK 自动完成：

```text
Java record
    ↓
JSON Schema
    ↓
Tool Catalog
    ↓
Model Tool Call
    ↓
Schema Validation
    ↓
Policy / Approval
    ↓
Java Method
```

[Java Tool Guide →](docs/advanced/tools.md)

### 原生 HITL 与安全恢复

Agent 可以在执行过程中暂停，等待：

- Clarification
- Confirmation
- Approval

这些正常 Interaction / Pause 状态可以持久化，并在进程重启后继续。

对于已经 Dispatch、但最终结果无法确认的 Side Effect，Runtime 不会冒险自动重放：

```text
Known result
    → continue

Waiting for approval
    → persist
    → restart
    → continue

Unknown side effect
    → fail closed
    → do not blindly replay
```

Haifa Agent 追求的不是“任何 Crash 都假装无缝恢复”，而是**恢复已经知道的事实，不猜测不知道的事实**。

[Persistence & Recovery →](docs/core-components/persistence-and-recovery.md)

### 多 Provider，但不偷偷降级

当前已经提供或验证的 Model Integration 包括：

- DeepSeek
- Google Gemini
- OpenAI-compatible API
- Anthropic-style API
- 多个 OpenAI-compatible Provider Binding

Run 创建时会冻结实际 Model Binding。

如果某个 Provider 或 Binding 不可用，Haifa Agent 会显式失败，而不是偷偷换一个 Model 继续运行。

[Model Providers →](docs/advanced/model-providers.md)

---

## 已经能做什么

| 能力 | 当前支持 |
| --- | --- |
| Conversation / Run | Multi-turn Conversation、异步 Run、Cancel、Resume |
| Streaming | Assistant Output Stream、Durable Run Event |
| Java Tool | Typed Java record、自动 Schema、Tool Loop |
| Structured Output | Java record 作为 Final Output Contract |
| MCP | MCP Client、Tool Discovery / Import、stdio / HTTP；纯 Java 用 `McpServerSpec` 声明式接入 |
| Skill | `SKILL.md`、Progressive Disclosure、Resource Read |
| HITL | Clarification、Approval、ASK / ALLOW / DENY |
| Persistence | SQLite、Conversation / Run / Interaction State |
| Memory | Candidate、Review、Formal Memory |
| Artifact | 显式 Artifact Export 与 Metadata |
| Workspace | Authorized Directory、File Tool |
| Execution | Shell、Build、Test、git、gh |
| Web | Web Search / Fetch Integration |
| Model | DeepSeek、Gemini、OpenAI-compatible 等 |
| Spring | Spring Boot Starter、JavaTool Bean 自动装配 |

Haifa Agent 不会因为“平台化”而默认开启全部 Capability。

产品只装配真正需要的能力。

---

## Architecture

Haifa Agent 的核心结构很简单：

```text
       Coding Agent       Personal Assistant        Your App
             \                  |                      /
              \                 |                     /
               └──────── Haifa Agent SDK ───────────┘
                              |
                              ▼
                         Agent Runtime
                              |
          ┌─────────┬─────────┼─────────┬─────────┐
          ▼         ▼         ▼         ▼         ▼
        Model      Tool      Skill     Memory    Policy
          │         │         │         │         │
          └─────────┴──────┬──┴─────────┴─────────┘
                           ▼
                       Integrations
                           |
       DeepSeek · Gemini · MCP · SQLite · Web · Host
```

三个基本原则：

1. **Core / Runtime / SDK 保持 Pure Java**
2. **Provider、Spring、SQLite、MCP 位于 Adapter / Integration 边界**
3. **Coding Agent 与 Personal Assistant 是同一个 Runtime 上的不同 Product Assembly**

不会因为某个产品需要某项功能，就自动把产品概念下沉进 Runtime。

[Architecture Overview →](docs/architecture/overview.md)

[Runtime & Module Boundaries →](docs/architecture/runtime-and-module-boundaries.md)

---

## Coding Agent

Coding Agent 不是 SDK Demo，而是 Haifa Runtime 的实际产品之一。

它把：

```text
Model
 + File Tools
 + Shell
 + git / gh
 + Workspace
 + Skill
 + MCP
 + Policy / Approval
 + Persistence
```

组合成一个可以长期使用的 Terminal Coding Agent。

典型任务：

```text
> 分析这个项目为什么 Windows CI 比 Linux 慢

> 修复 failing test，并运行相关验证

> 阅读这个模块，找出 Runtime 与 Product Layer 耦合的位置

> 修改实现，但不要创建 commit
```

Model 自己负责理解任务、规划步骤和解释 Command Result。

Runtime 只负责真正应该由 Runtime 保证的事情：

- Tool Contract
- Workspace Boundary
- Approval
- Cancellation
- Budget
- Unknown Side Effect
- Lifecycle
- Persistence

[了解 Coding Agent →](docs/applications/coding-agent.md)

---

## Personal Assistant

Personal Assistant 展示了另一种完全不同的 Product Assembly。

它重点组合：

```text
Conversation
 + Model Selection
 + Tool / Skill / MCP
 + Memory
 + Web
 + Mission
 + Deep Research
 + Artifact
 + Local Web UI
```

PA 使用本地 Spring Boot Server + React Web，并默认运行在 Loopback Trusted-local 环境。

它不是一个面向公网、多租户的通用 Agent Server。

[了解 Personal Assistant →](docs/applications/personal-assistant.md)

---

## SDK 示例

仓库包含一组可以直接运行的 SDK Example。

```text
haifa-agent-sdk-example
├── basic
│   ├── HelloHaifa
│   ├── MultiTurnConversationExample
│   └── AgentReuseLifecycleExample
│
├── intermediate
│   ├── TypedJavaToolExample
│   ├── MultiToolCollaborationExample
│   ├── StructuredOutputExample
│   ├── MultiModelProviderExample
│   └── PromptDiagnosticsExample
│
└── advanced
    ├── ConversationManagementExample
    ├── IdempotencyAndRevisionExample
    ├── RunOutputStreamingExample
    ├── RunEventJournalExample
    ├── RunQueryControlExample
    ├── TrustedCallerExample
    ├── AssemblyDiagnosticsExample
    └── SqliteDurableReferenceAssemblyExample
```

普通 Example 默认不访问真实 Provider；当前只有 `basic.HelloHaifa` 明确使用真实 Provider，并要求 `DEEPSEEK_API_KEY`。

[SDK Examples →](haifa-agent-applications/haifa-agent-sdk-example/README.md)

---

## 当前边界

Haifa Agent 当前重点面向：

> **单机 / 本地可信环境中的 Java Agent Runtime 与产品开发。**

`0.1.2-SNAPSHOT` 暂不提供：

- Distributed Worker / Control Plane
- Graph / Workflow Runtime
- Enterprise IAM / Approval Workflow
- Built-in Container / gVisor / microVM Sandbox
- MCP Server Hosting
- 通用公网 Multi-tenant Agent Server

当前 `host-guarded` Execution 提供的是受控 Host Process Execution，而不是 OS Kernel-level Isolation。

如果需要执行完全不可信代码，应把 Haifa Agent 放入外部 VM / Container 等安全边界。

[Security →](docs/reference/security.md)

---

## 文档

第一次使用：

- [Installation](docs/get-started/installation.md)
- [Quickstart](docs/get-started/quickstart.md)
- [Key Concepts](docs/get-started/key-concepts.md)

深入理解：

- [Agent Runtime](docs/core-components/agent-runtime.md)
- [Capabilities](docs/core-components/capabilities.md)
- [Architecture](docs/architecture/overview.md)

开发 Agent：

- [Java Tools](docs/advanced/tools.md)
- [Skills & MCP](docs/advanced/skills-and-mcp.md)
- [Structured Output](docs/advanced/structured-output.md)
- [Model Providers](docs/advanced/model-providers.md)

产品：

- [Coding Agent](docs/applications/coding-agent.md)
- [Personal Assistant](docs/applications/personal-assistant.md)

完整文档入口：

**[docs/README.md →](docs/README.md)**

---

## 构建与测试

普通测试默认不会访问真实 Model、MCP 或 Web Provider。

macOS / Linux：

```bash
# 受影响模块测试
./build-support/scripts/invoke-haifa-maven.sh --layer L2 -- \
  -pl :haifa-agent-runtime-core -am test

# 最终本地门禁
./build-support/scripts/invoke-haifa-maven.sh --layer L3 -- \
  -Pci-fast clean verify
```

Windows PowerShell：

```powershell
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L2 '--' `
  -pl :haifa-agent-runtime-core -am test

.\build-support\scripts\invoke-haifa-maven.ps1 --layer L3 '--' `
  -Pci-fast clean verify
```

更完整的构建与测试规则见：

[build-support/README.md](build-support/README.md)

---

## Contributing

开始修改前，请先阅读：

- [AGENTS.md](AGENTS.md)
- 对应模块的 `README.md`
- 对应模块 `pom.xml`
- 相关 Architecture Test

功能开发使用 `feat-*` 分支并发起 Pull Request。

代码与公开文档应在同一个 PR 中同步演进。

Haifa Agent 仍处于快速发展阶段。相比提前构建大量“未来可能需要”的抽象，我们更倾向于从真实 Product、真实 Failure 和真实 Consumer 出发逐步演进。

[Design Principles →](docs/architecture/design-principles.md)
