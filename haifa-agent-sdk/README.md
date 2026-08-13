# Haifa Agent SDK

`AgentRuns.plan(runId)` delegates the caller-scoped Runtime plan query and returns an immutable
view only when an authoritative plan exists. Product UIs can therefore show real Todo progress
without inferring steps from model text or activity counts.

Products may register trusted `ProductRunProfile` values for bounded one-shot internal work such as Mission
planning. A named profile freezes its model, Run type, budget, limits, version, and provider-neutral structured
request options into the ordinary Runtime configuration snapshot. `AgentRuns.start(request)` still enters the
single Runtime start path; a profile is selection data, not a second executor or product-specific Run state machine.
Profiles may also freeze an optional Tool allowlist. An absent allowlist inherits the Agent definition, an explicit
empty allowlist freezes no Tools, and a non-empty allowlist must be a subset of the Agent definition.

面向上层 Agent 产品的纯 Java 高层装配与应用边界。`HaifaAgent` 表示装配完成、可被宿主长期持有
并负责资源生命周期的产品 Runtime 实例；它不是 Agent Definition，也不是某一次 Run。SDK 通过可信
`ProductProfile` 和类型化 `ProductContribution` 确定性解析产品能力，构建唯一 `AgentRuntime`，
并提供产品中立的 Conversation Session 服务。

SDK 不替代 Core/Runtime 状态机，不包含 Spring、SQLite、MCP SDK 或具体模型 Provider，也不会
扫描 Classpath 自动导入能力。具体实现仍由对应 Integration/Application 模块提供，并在进程启动时
显式注册。

首次体验可直接使用相邻的 `haifa-agent-sdk-starter`；它提供模型无关的快速入口，并安全地默认装配
DeepSeek V4 Flash 与进程内 Store。需要持久化、身份、治理或自定义 Provider 时再使用本模块的完整
装配 API。

## 成功路径

Starter 的单次便利调用仍使用同一 Conversation/Run 路径：

```java
try (var agent = HaifaAgentStarter.builder()
        .name("weather-agent")
        .instructions("Use disclosed Tools for weather questions.")
        .tool(new WeatherTool())
        .build()) {
    var response = agent.chat("What is the weather in Shanghai?").await();
    System.out.println(response.text());
}
```

`name` 是构建后不可变的展示元数据，并作为便利 `chat()` 新建 Conversation 的默认 display name；它不进入
Prompt、模型或 Tool 选择、Policy、Checkpoint 或恢复协议。Agent `description` 暂不暴露。`chat()` 自动生成
的幂等键只属于当前进程内这次便利调用；需要重试、继续会话、
revision、取消或事件订阅时，使用下面的显式 Conversation/Run API。

```java
try (HaifaAgent agent = HaifaAgents.builder()
        .product(profile)
        .contribute(model)
        .contribute(persistence)
        .contribute(conversation)
        .build()) {
    ConversationRecord started = agent.conversations()
            .start(new StartConversationCommand("start-1", "New chat", "Hello"));
    AgentRunSnapshot completed = agent.runs()
            .await(started.activeRunId().orElseThrow());
}
```

`start`/`submit` 同步完成命令接收和 Run 创建，Run 本身异步执行；`await` 委托同一个 Runtime，
不复制 Run 状态。`HaifaAgent` 拥有本次装配已成功初始化的 Contribution 和本地 Scheduler：
关闭时先停止调度，再按能力确定性初始化顺序逆序关闭 Contribution；重复关闭无副作用。构建中途失败
只释放已经成功初始化的资源。

## 类型化最终输出

需要结构化最终结果时，可以直接把有界 Java record 作为本次 Run 的输出契约：

```java
public record TripPlan(String city, int days, List<String> activities) {}

AgentResponse<TripPlan> response =
        agent.chat("Plan a two-day trip.", TripPlan.class).await();
TripPlan plan = response.value();
```

SDK 复用 Java Tool 的 record Schema/Codec，生成内容寻址 Schema ID/version，并把精确 JSON Schema 冻结进
Conversation 创建的 Run 配置。Provider Adapter 显式映射该要求；Runtime 只在模型没有 Tool Call 的最终
回答上校验冻结 Schema，把结构化 Map 写入权威 `AgentRunResult`，然后 SDK 才从该持久结果解码 record。
SDK 不从未经校验的文本 JSON 直接构造业务对象。

类型化值只存在于终态 `AgentResponse<T>`；流式文本、Tool Call、Checkpoint 和中间恢复状态不会伪装成
类型化 partial output。Schema 不匹配、Provider 不支持、拒答和截断分别收敛为安全稳定的 Runtime 错误
分类，失败响应调用 `value()` 会 fail closed。当前只支持既有 Java record Schema/Codec 的有界类型集合，
该便利 API 尚未声明 Stable API。

## 类型化 Java Tool

普通 SDK 使用方可以用 Java record 声明输入输出，并按单个 Tool 注册；不需要手工创建 digest、binding、
Catalog、Invoker 或 Tool Platform Contribution：

```java
public record WeatherRequest(String city) {}

public record WeatherResponse(String forecast) {}

public final class WeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
    private static final JavaToolSpec<WeatherRequest, WeatherResponse> SPEC =
            JavaToolSpec.builder("weather.get", WeatherRequest.class, WeatherResponse.class)
                    .alias("weather_get")
                    .description("Get the current weather for a city")
                    .pure()
                    .build();

    @Override
    public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
        return SPEC;
    }

    @Override
    public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
        return new WeatherResponse(weatherClient.current(input.city()));
    }
}
```

```java
HaifaAgent agent = HaifaAgents.builder()
        .product(profile)
        .contributeAll(baseContributions)
        .tool(new WeatherTool())
        .build();
```

SDK 会为 record 生成有界 JSON Schema，完成 Map 与 record 的双向转换，把 Tool alias 加入本次装配的
有效 Product Profile，并与已有 Catalog 确定性合并。调用仍进入统一的 Schema、Policy、Approval、
Credential、Journal 和 Tool Pipeline。`Optional<T>` 只用于可选的直接 record component；不支持递归
record、通配泛型、任意 POJO 或非 String Map key。注解式 Tool 不属于当前版本。

## Product Profile 与装配

- `ProductProfile` 冻结产品 ID/版本、Definition/Profile 引用、预算、限制、指令、Capability
  Requirement 及 Tool/Skill/Extension allowlist，并校验 canonical SHA-256 digest。
- 每个 Capability 使用 `NONE`、`OPTIONAL` 或 `REQUIRED`；未声明能力等价于 `NONE`。
- Contribution 必须声明稳定坐标、能力 ID、配置 digest、生产适用级别和安全摘要。解析拒绝重复坐标、
  多个兼容实现、allowlist 外实现、生产场景中的 Test-only Provider，以及 `NONE` 能力泄漏。
- Contribution 注册顺序不影响选择和 assembly digest。装配结果作为 Runtime
  `ResolvedCapabilities` 的 `product.profile`、`product.assembly` 及精确 Contribution binding
  写入既有配置快照，因此每个 Run 冻结产品语义。
- Tool 和 Skill 只有在 Profile 明确允许且冻结 Catalog 中存在时才进入 Runtime。MCP 先由 Integration
  完成连接、发现、schema/risk 映射和逐项 allowlist，再作为统一 Tool Catalog 的一部分注入；SDK
  不提供绕过 Tool Pipeline 的 MCP 执行通道。
- 命名 `ProductRunProfile` 只由可信产品装配注册，ID 冲突和未知 Profile fail closed；模型请求选项
  只接受非空 JSON-compatible 标量、Map 和 List，并在配置摘要与物理模型调用中保持一致。

## Conversation 公共边界

一个 Conversation 以 Core `AgentSessionId` 作为权威身份，一个会话可包含多个 Run，但最多一个活动
Run。当前 API 提供：

- `start`、`submit`、`rename`、`archive`、`unarchive`；
- `find`、可信 Caller 范围内的稳定 Cursor 列表/搜索；
- 只返回用户可见 User/Assistant 内容的 Turn Cursor 分页；
- 写命令的 caller-scoped idempotency、request digest、expected revision 与单活动 Run 冲突；
- Runtime 已创建 Run、投影尚未完成，以及活动 Run 已终态时的查询期恢复。

删除、回收站、Tree/Fork/Clone、Follow-up Queue 和 Retention 不属于该公共边界。SQLite 实现位于
`haifa-agent-store-sqlite`，SDK 自身不依赖 SQLite；`InMemory` 实现只用于开发和确定性测试。

## 进程内 Prompt Diagnostics

`agent.runs().promptDiagnostics(runId)` 从 Runtime 实际 `ContextTrace` 读取脱敏事实：最终顺序、component
ID、layer/role、version、SHA-256 digest、token estimate 和来源类别。它不返回 Prompt、用户消息、
Memory 或 Tool 正文。查询先沿用当前 Caller 的 Run 授权；未授权、尚未构建 Context、或进程重启后
统一返回 `PROMPT_DIAGNOSTICS_UNAVAILABLE`。该能力没有数据库 Migration、Checkpoint 字段或跨重启
兼容承诺。

## 边界

- 公共 API 不暴露 `RuntimeCoreBuilder`、Runtime Core 内部 bootstrap 类型、SQLite/MyBatis、
  Spring、Provider Client、`Path`、Connection 或 Credential 明文。
- Caller 的 Tenant/Principal 来自可信 `SdkCallerProvider`，不从 Conversation 命令正文接收。
- 诊断只包含逻辑产品/能力/Contribution 标识和 digest，不包含 Prompt、Memory、Tool 正文、
  Provider 原始配置或绝对路径。
- SDK 不依赖任何产品 Application。Coding、Personal、Document 等上层产品用各自
  `ProductProfile` 选择不同模块实现，但都复用同一 Runtime 状态机和 SDK 装配器。

## 政策、扩展与错误

SDK 的 Run Snapshot/Result 直接暴露 Core `AgentError` 的类型化 code、默认安全 message、
category、retryability、details 和 diagnosticId；调用方无需解析字符串，也不会收到 Runtime
Core 或 Provider 异常。同步请求失败属于 `RuntimeApiErrorCode`，异步 Run 失败属于
`AgentErrorCode`。

- `ProductPolicies` 将 Memory 人工审查与查询边界、Artifact 配额/Media Type/本地容量门禁及
  Execution 主机/网络/并发/超时政策冻结进 Profile canonical digest；本阶段不允许关闭
  Memory Candidate 人工审查。
- Model、Tool Platform、Skill、MCP Tool binding、Context、Memory、Artifact、Policy、Approval、
  Credential 和 Execution/Sandbox 均通过显式 typed Contribution 注册。MCP alias 还必须同时
  出现在 Profile allowlist 与统一 Tool Catalog 中，不存在第二条 MCP 执行通道。
- 应用级 Java Tool 通过 `HaifaAgentBuilder.tool(JavaTool)` 逐个注册；SDK 在构建时生成并合并内部
  Tool Platform Contribution，应用无需理解 Catalog digest 与 frozen binding。
- 产品可通过 `publicToolPolicyDecorator` 对 Runtime 已选定的公共 Tool Policy 做有界装饰；装饰器
  必须为自己拥有的精确动作生成 request-bound Decision，并把其它动作委托给既有 Policy，不得建立
  第二条 Tool 执行通道。
- `HaifaAgentException` 及 `ConversationException` 对外只暴露安全的 `code`、`operation` 和
  `correlation`。Conversation Adapter、SQLite/Runtime 底层异常和输入正文不会进入公共错误消息。
- `HaifaAgent.memories()` 暴露受 Product Profile、可信 `SdkCaller` 与权限约束的产品级
  propose/revise/approve/reject/invalidate/list API；调用命令不能注入 Tenant、Principal 或 Reviewer。
- `HaifaAgent.memory()` 与 `HaifaAgent.artifacts()` 只在 Profile 选中了对应 typed Contribution
  时返回应用服务；SQLite Product Contributions 已提供 Memory 与 Artifact 的单机持久化实现基线。

当前开发范围由 `docs/20-agent-sdk-product-session-memory-foundation.md` 定义。
