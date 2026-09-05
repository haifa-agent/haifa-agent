# Haifa Agent Standalone Consumer Examples

This non-Reactor Maven build consumes installed Haifa Agent `0.1.0-SNAPSHOT` artifacts exactly as an
external application would. It is tracked by the main repository so that it cannot drift out of
view, but it deliberately does not inherit the main repository parent POM or join its Reactor.

The standalone build contains complete, production-pattern consumer applications rather than a
second catalog of SDK teaching snippets.

---

## 📚 Examples Overview

| Application | Module | Core Concepts | Run Command | Description |
| :--- | :--- | :--- | :--- | :--- |
| **PureJavaQuickstartApplication** | `pure-java-quickstart` | `HaifaAgentStarter`, `JavaTool`, ReAct Loop | `mvn exec:java -Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaQuickstartApplication` | Single-turn chat with typed weather tool execution |
| **PureJavaStreamingApplication** | `pure-java-quickstart` | Real-time Streaming, `subscribeOutput`, Token Deltas | `mvn exec:java -Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaStreamingApplication` | Terminal typewriter-style streaming token output |
| **PureJavaStructuredOutputApplication** | `pure-java-quickstart` | Structured Output, Java Record, Frozen Schema | `mvn exec:java -Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaStructuredOutputApplication` | Type-safe schema validation extracting a Java Record |
| **PureJavaVisionApplication** | `pure-java-quickstart` | DeepSeek Vision, Direct Image Upload, Multimodal | `mvn exec:java '-Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaVisionApplication'` | Explains the story in an image uploaded directly without external URLs |
| **SpringBootQuickstartApplication** | `spring-boot-quickstart` | Spring Boot, Tool Beans, REST API, Web SSE | `mvn -pl :haifa-agent-spring-boot-quickstart spring-boot:run` | Web service exposing REST and Server-Sent Events (SSE) |

---

## 🚀 Quick Start & Offline Verification

### 1. Verify without network access (100% Deterministic)

Install the public artifacts from the repository root, then build and verify this independent project:

```bash
# 1. From repository root: install public artifacts
./mvnw \
  -pl :haifa-agent-bom,:haifa-agent-spring-bom,:haifa-agent-sdk-starter,:haifa-agent-spring-boot-starter \
  -am -DskipTests install

# 2. In examples directory: clean and verify offline
cd examples/haifa-agent-example
./mvnw clean verify
```

> **Determinism Guarantee**: All automated tests run 100% offline without contacting external LLM providers or requiring credentials.

---

## 🌐 Running with Real LLMs (DeepSeek & Custom Providers)

### 1. Unified Default Provider & Model

All standalone consumer examples are pre-configured to use **DeepSeek** as the default model provider:
- **Provider**: DeepSeek (`https://api.deepseek.com`)
- **Default Model**: `deepseek-v4-flash`
- **Default Credential Environment Variable**: `DEEPSEEK_API_KEY`
- **Why `deepseek-v4-flash`**: High throughput, ultra-low latency, native tool calling, and strict schema validation compatibility.

---

### 2. Setting Your API Key

Never place API keys directly in source code, POM files, configuration properties, or git history. Use one of the following safe methods:

#### Option A: Direct Environment Variable
* **Linux / macOS (Bash / Zsh)**:
  ```bash
  export DEEPSEEK_API_KEY="sk-your-deepseek-api-key"
  ```
* **Windows (PowerShell)**:
  ```powershell
  $env:DEEPSEEK_API_KEY = "sk-your-deepseek-api-key"
  ```

#### Option B: Load from a Local Secret File (Recommended to prevent shell history leaks)
If your key is stored in a private text file (e.g. `D:\workspace\ss-deepseek.txt` or `~/.deepseek_key`):
* **Linux / macOS (Bash / Zsh)**:
  ```bash
  export DEEPSEEK_API_KEY=$(cat ~/.deepseek_key | tr -d '\r\n')
  ```
* **Windows (PowerShell)**:
  ```powershell
  $env:DEEPSEEK_API_KEY = (Get-Content -Path 'D:\workspace\ss-deepseek.txt' -Raw).Trim()
  ```

#### Option C: Custom Credential Variable Name
If your deployment uses a custom environment variable (e.g. `ENTERPRISE_AI_TOKEN`):
* **In Pure Java**:
  ```java
  try (var agent = HaifaAgentStarter.builder()
          .credentialEnvironmentVariable("ENTERPRISE_AI_TOKEN")
          .build()) {
      // ...
  }
  ```
* **In Spring Boot (`application.properties`)**:
  ```properties
  haifa.agent.model.credential-environment-variable=ENTERPRISE_AI_TOKEN
  ```

---

### 3. Specifying & Switching Providers and Models

Haifa Agent uses type-safe, immutable model snapshots (`ResolvedModelSnapshot`), allowing you to switch between model providers (such as Alibaba Bailian, Volcengine Ark, Kimi, Zhipu GLM, SiliconFlow) without rewriting business logic.

#### A. In Pure Java (`pure-java-quickstart`)

You can register custom models using `OpenAiCompatibleModelConfiguration` or `HaifaAgentStarterBuilder.model(...)`:

```java
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration;
import io.haifa.agent.starter.HaifaAgentStarter;
import java.net.URI;

// Example: Switch to Alibaba Bailian (Qwen)
var qwenConfig = OpenAiCompatibleModelConfiguration.builder()
        .providerId("aliyun-bailian")
        .modelId("qwen-plus")
        .endpoint(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        .dialect(OpenAiCompatibleDialects.ALIYUN_BAILIAN)
        .credentialEnvironmentVariable("DASHSCOPE_API_KEY")
        .build();

try (var agent = HaifaAgentStarter.builder()
        .model(qwenConfig)
        .defaultModel("qwen-plus")
        .instructions("You are a helpful assistant powered by Qwen.")
        .build()) {
    var response = agent.chat("Hello from Qwen!").await();
    System.out.println(response.text());
}
```

#### B. In Spring Boot (`spring-boot-quickstart`)

1. **Via `application.properties`**:
   ```properties
   haifa.agent.name=standalone-office-agent
   haifa.agent.instructions=Answer concisely. Use office_hours for schedule questions.
   haifa.agent.model.credential-environment-variable=DEEPSEEK_API_KEY
   haifa.agent.model.connect-timeout=15s
   ```
2. **Via `HaifaAgentStarterCustomizer` Spring Bean**:
   Inject custom models, timeouts, or tool pipelines programmatically:
   ```java
   @Configuration
   public class CustomAgentConfiguration {
       @Bean
       public HaifaAgentStarterCustomizer customModelCustomizer() {
           return builder -> {
               builder.instructions("Enterprise Customer Service Specialist");
               // builder.model(...); // Register custom models
           };
       }
   }
   ```

---

## 📖 Detailed Examples & Interaction Guide

> **PowerShell Parameter Note**: On Windows PowerShell, wrap Maven `-D...` parameters in single quotes, e.g. `'-Dexec.mainClass=...'`.

### 1. Pure Java Quickstart (`PureJavaQuickstartApplication`)

Demonstrates pure Java setup using `HaifaAgentStarter` with a strongly typed `JavaTool` (`WeatherTool`).

* **Linux / macOS**:
  ```bash
  ./mvnw -pl :haifa-agent-pure-java-quickstart \
    -Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaQuickstartApplication exec:java
  ```
* **Windows PowerShell**:
  ```powershell
  & .\mvnw.cmd -pl :haifa-agent-pure-java-quickstart \
    '-Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaQuickstartApplication' exec:java
  ```

* **What you'll learn**:
  * Initializing `HaifaAgent` with fluent builder configuration.
  * Registering strongly typed tools with schema validation (`WeatherTool`).
  * Running blocking chat calls with `.chat(...).await()`.
* **Try asking**:
  * `"What is the weather in Shanghai?"`
  * `"Check the weather for Hangzhou and summarize it in one sentence."`

---

### 2. Pure Java Token Streaming (`PureJavaStreamingApplication`)

Demonstrates low-latency token streaming directly in the terminal using Haifa's reactive event subscription.

* **Linux / macOS**:
  ```bash
  ./mvnw -pl :haifa-agent-pure-java-quickstart \
    -Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaStreamingApplication exec:java
  ```
* **Windows PowerShell**:
  ```powershell
  & .\mvnw.cmd -pl :haifa-agent-pure-java-quickstart \
    '-Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaStreamingApplication' exec:java
  ```

* **What you'll learn**:
  * Subscribing to output events using `agent.runs().subscribeOutput(runId, cursor, listener)`.
  * Filtering `AgentRunOutputEventType.ASSISTANT_TEXT_DELTA` for real-time typewriter output.
  * Synchronizing completion with `agent.runs().await(runId)`.
* **Try asking**:
  * `"Explain why the sky is blue in two sentences."`
  * `"Tell a short story about an autonomous robot."`

---

### 3. Pure Java Structured Output (`PureJavaStructuredOutputApplication`)

Demonstrates extracting typed Java 17+ records from natural language prompts with frozen JSON Schema validation.

* **Linux / macOS**:
  ```bash
  ./mvnw -pl :haifa-agent-pure-java-quickstart compile \
    -Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaStructuredOutputApplication exec:java
  ```
* **Windows PowerShell**:
  ```powershell
  & .\mvnw.cmd -pl :haifa-agent-pure-java-quickstart compile \
    '-Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaStructuredOutputApplication' exec:java
  ```

* **What you'll learn**:
  * Defining public Java records as schemas (e.g. `WeatherForecastReport`).
  * Calling `agent.chat(prompt, WeatherForecastReport.class).await()`.
  * Type-safe extraction via `.value()` guaranteed by the runtime validator.
* **Try asking**:
  * `"Generate a structured JSON weather report for Tokyo in Autumn."`
  * `"Extract a JSON object with city, temperature, conditions, and attire recommendations."`

---

### 4. Pure Java Multimodal Vision (`PureJavaVisionApplication`)

Demonstrates multimodal vision understanding using the DeepSeek Vision model (`deepseek-v4-flash-vision-exp`) with **direct image data upload** (Base64 Data URI) without requiring external image URLs or public hosting.

* **Image Fixture**: Bundles the Personal Assistant smoke test fixture `indoor-door-people.webp` (an indoor photo showing people and a door in an office corridor).
* **Upload Method**: Raw bytes are loaded from the classpath, registered into `InMemoryImageStore`, and uploaded inline as standard OpenAI-compatible `data:image/webp;base64,...` payload.

* **Linux / macOS**:
  ```bash
  ./mvnw -pl :haifa-agent-pure-java-quickstart compile \
    -Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaVisionApplication exec:java
  ```
* **Windows PowerShell**:
  ```powershell
  & .\mvnw.cmd -pl :haifa-agent-pure-java-quickstart compile \
    '-Dexec.mainClass=io.haifa.example.consumer.plain.PureJavaVisionApplication' exec:java
  ```

* **What you'll learn**:
  * Configuring vision models with `HaifaAgentStarter.builder().defaultModel("deepseek-v4-flash-vision-exp")`.
  * Using `InMemoryImageStore` to manage raw image bytes without external object storage.
  * Passing image inputs via `agent.chat(prompt, imagePart).await()`.
  * DeepSeek vision model's scene recognition, character interaction analysis, and narrative generation.
* **Try asking**:
  * `"请仔细观察这张图片，详细解释画面中正在发生的故事。"`
  * `"Describe what the people are doing and the mood of the scene."`

---

### 5. Spring Boot Web & SSE Streaming (`SpringBootQuickstartApplication`)

Demonstrates Spring Boot 3 auto-configuration, automatic Tool Bean discovery, and exposing REST + Server-Sent Events (SSE) endpoints.

* **Start the Application**:
  ```bash
  ./mvnw -pl :haifa-agent-spring-boot-quickstart spring-boot:run
  ```
  The application starts on `http://localhost:8080`.

#### A. Real-Time Web SSE Stream (Browser & Client Friendly)

```bash
curl -N "http://localhost:8080/api/chat/stream?prompt=Hello+Haifa+Agent"
```

Live stream output:
```text
event:delta
data:Hello! How can I help you today?

event:complete
data:Hello! How can I help you today?
```

#### B. JSON Chat & Multi-turn Conversation

Start a new conversation:
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Use office_hours to check the Shanghai office schedule."}'
```

Response:
```json
{
  "sessionId": "01a06c96-125d-70db-a42d-c6af340a6454",
  "runId": "01a06c96-128a-7bd8-91bc-6c451f21e0c8",
  "answer": "The Shanghai office is open from 09:00 to 17:00."
}
```

Continue the existing conversation by passing `sessionId`:
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Can I visit at 10:00 AM?", "sessionId": "01a06c96-125d-70db-a42d-c6af340a6454"}'
```

#### C. Optional Console Runner Mode

To run the one-shot console runner instead of keeping the web server alive:
* **Linux / macOS**:
  ```bash
  ./mvnw -pl :haifa-agent-spring-boot-quickstart spring-boot:run \
    -Dspring-boot.run.arguments="--example.runner.enabled=true"
  ```
* **Windows PowerShell**:
  ```powershell
  & .\mvnw.cmd -pl :haifa-agent-spring-boot-quickstart spring-boot:run \
    '-Dspring-boot.run.arguments=--example.runner.enabled=true'
  ```

---

## 🧭 Architecture Dual-Track: Which Example Should I Read?

Haifa Agent provides two distinct tiers of examples:

1. **Standalone Consumer Applications (`examples/haifa-agent-example`)** [This Directory]:
   * Focus: External consumer experience, Maven BOM packaging, Spring Boot auto-configuration, Web REST/SSE endpoints.
   * Target audience: Application developers embedding Haifa Agent into their services.
2. **SDK Deep-Dive Reference (`haifa-agent-applications/haifa-agent-sdk-example`)**:
   * Focus: Authoritative, granular SDK features and runtime mechanics.
   * Topics covered:
     * *Basic*: Conversation lifecycle, agent reuse, multi-turn state.
     * *Intermediate*: Multi-tool loops, record schemas, prompt diagnostics, multi-model selection.
     * *Advanced*: Failover, safe error handling, SQLite persistence (`SqliteDurableReferenceAssemblyExample`), run event journals, idempotent execution.

---

## 🔮 Ecosystem & Advanced Orchestration Roadmap

For developers planning enterprise deployments, the following architectural extensions are designed for seamless integration:

### 1. Model Context Protocol (MCP) Integration
* **Pattern**: Adapt external MCP Servers (Filesystem, PostgreSQL, GitHub) into Haifa's `JavaToolSpec`.
* **Mechanism**: Use an MCP client transport (StdIO or SSE) to discover tools dynamically and register them with `HaifaAgentStarter.builder().tool(...)`.
* **Boundary**: Keep MCP protocol adapters decoupled from core runtime to maintain zero-dependency pure Java guarantees.

### 2. Human-In-The-Loop (HITL) & Tool Policy Interruption
* **Pattern**: Pause execution before performing high-risk tool actions (e.g. database mutations, wire transfers) pending human approval.
* **Mechanism**: Leverage Haifa's `ToolPolicy` and `ToolPolicyDeniedException` / approval events. The session transitions to a suspended state, and execution is resumed via `agent.conversations().submit(...)` once the approval token is granted.
