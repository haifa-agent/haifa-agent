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
`ProductProfile` 加一组显式 typed 组件装配唯一 `AgentRuntime`，并提供产品中立的 Conversation
Session 服务。

SDK 不替代 Core/Runtime 状态机，不包含 Spring、SQLite、MCP SDK 或具体模型 Provider，也不会
扫描 Classpath 自动导入能力。具体实现仍由对应 Integration/Application 模块提供，并在进程启动时
显式注册。

`AgentRuns.recover(runId)` 将失去旧物理执行者的持久 Run 交回同一 Runtime 恢复协议；SDK 不复制
Checkpoint、Tool Journal 或幂等重放判断。仍由当前实例拥有的 Run 会被拒绝，防止双重执行。

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
try (HaifaAgent agent = HaifaAgents.builder(profile)
        .model(model)
        .persistence(persistence)
        .conversation(conversation)
        .build()) {
    ConversationRun started = agent.conversations()
            .start(new StartConversationCommand("start-1", "New chat", "Hello"));
    AgentRunSnapshot completed = agent.runs()
            .await(started.runId());
}
```

`start`/`submit` 同步完成命令接收和 Run 创建，Run 本身异步执行；`await` 委托同一个 Runtime，
不复制 Run 状态。需要一个组件就显式装配一个：Model、Persistence、Conversation 缺失时构建直接
fail closed；Memory、Artifact、Policy、Approval、Credential 等可选组件缺失即对应能力不存在。
`HaifaAgent` 拥有本地 Scheduler 和已装配的可关闭组件：关闭时先停止调度，再逆序关闭组件；重复关闭
无副作用。构建中途失败只关闭已经装配成功的资源。

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
Catalog 或 Invoker：

```java
public record WeatherRequest(String city) {}

public record WeatherResponse(String forecast) {}

public final class WeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
    private static final JavaToolSpec<WeatherRequest, WeatherResponse> SPEC =
            JavaToolSpec.builder("weather_get", WeatherRequest.class, WeatherResponse.class)
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
HaifaAgent agent = HaifaAgents.builder(profile)
        .model(model)
        .persistence(persistence)
        .conversation(conversation)
        .tool(new WeatherTool())
        .build();
```

SDK 把每个 Java Tool 一次性转换为 Tool Core 的 `ToolDefinition` 加 `ToolProvider`，注册进统一的
`ToolCatalogBuilder` 并只 `freeze()` 一次，随后直接使用 Tool Core 的 Catalog、Invoker 与 Schema 校验器：SDK
不合并 Catalog、不重算已冻结 binding 的 catalog digest，也不 multiplex 校验器。record 的有界 JSON Schema
生成、Map 与 record 的双向转换、下划线 Tool 名称加入本次装配的有效 Tool allowlist（不改写 `ProductProfile`）
都保持不变。调用仍进入统一的 Schema、Policy、Approval、Credential、Journal 和 Tool Pipeline。`Optional<T>`
只用于可选的直接 record component；不支持递归 record、通配泛型、任意 POJO 或非 String Map key。注解式 Tool
不属于当前版本。

`JavaToolSpec` 只声明普通 Java Tool 需要的事实：name、input/output record、title、description、timeout，以及
`pure()` 或 `sideEffects(...)`。provider 身份、并发策略、资源、Credential、Approval、provenance 与 tags 由 SDK
Tool 平台固定，不再镜像到该入口；需要这些字段的 Tool 直接用 Tool API 的完整 `ToolDefinition` 注册。`pure()`
是唯一降低 risk、idempotency 与 approval 声明的入口，声明 side effect 会自动取消 pure 声明，其余情况保持
medium risk、unknown idempotency 与由 Policy 决定的审批。

已经持有 Tool platform 的宿主在该平台上注册自己的 Tool：`.toolPlatform(...)` 与 `.tool(...)` 同时使用会以
`JAVA_TOOL_PLATFORM_UNSUPPORTED` fail closed，而不是静默改写宿主平台的 Catalog 与 binding。

## Product Profile 与显式装配

- `ProductProfile` 只冻结产品身份（ID/版本）、Agent Definition 版本、instructions、单一
  `defaultRunProfile` 引用、预算、限制及 Tool/Skill allowlist。它不再保存 Policy、Capability
  Requirement、Contribution coordinate 或 product configuration digest；子系统的治理配置由对应
  typed 组件拥有，Runtime Configuration Snapshot 仍是唯一的执行冻结事实。
- `HaifaAgentBuilder` 直接接收 typed 组件：`model`、`persistence`、`conversation` 为必需；
  `toolPlatform`、`skillPlatform`、`memory`、`artifacts`、`policy`、`approval`、`credentials` 为可选。
  缺少必需组件时构建以稳定错误码失败，可选组件缺失即对应能力不存在；不再存在 candidate resolution、
  ambiguity、suitability 或 assembly digest。
- Tool 和 Skill 只有在 Profile 明确允许且冻结 Catalog 中存在时才进入 Runtime，否则构建以
  `TOOL_ALIAS_UNAVAILABLE` / `SKILL_ALIAS_UNAVAILABLE` 失败。MCP 先由 Integration
  完成连接、发现、schema/risk 映射和逐项 allowlist，再作为统一 Tool Catalog 的一部分注入；SDK
  不提供绕过 Tool Pipeline 的 MCP 执行通道。
- 命名 `ProductRunProfile` 只由可信产品装配注册，ID 冲突和未知 Profile fail closed；模型请求选项
  只接受非空 JSON-compatible 标量、Map 和 List，并在 Run 冻结配置与物理模型调用中保持一致。

## Conversation 公共边界

一个 Conversation 以 Core `AgentSessionId` 作为权威身份。Session、Run 与 Turn 事实只由 Runtime 拥有；
SDK Conversation 层只保存 display/index 元数据（display name、时间、revision，以及创建时从可信 Caller
复制的不可变授权/列表索引）。`ConversationRecord` 是读模型，其 `status` 由 Runtime
`AgentSession.status()` 派生：`ACTIVE`/`ARCHIVED` 正常返回，`CLOSED`/`DELETED` 视为不可用。当前 API 提供：

- `start`、`submit` 返回 `ConversationRun`（`ConversationRecord` 加本次 Run ID/version），
  `rename`、`archive`、`unarchive` 返回 `ConversationRecord`；
- `find`、可信 Caller 范围内的稳定 Cursor 列表/搜索；
- 只返回用户可见 User/Assistant 内容的 Turn Cursor 分页；
- 写命令的 caller-scoped idempotency、request digest 与 expected revision；`start` 通过 Runtime Run
  绑定在跨崩溃窗口内复用同一 Run，`rename`/`archive`/`unarchive` 通过 Runtime applied-command 记录保证
  exactly-once。

删除、回收站、Tree/Fork/Clone、Follow-up Queue 和 Retention 不属于该公共边界。SQLite 实现位于
`haifa-agent-store-sqlite`，SDK 自身不依赖 SQLite；`InMemory` 实现只用于开发和确定性测试。

## Run 输入（Steer）

`agent.runs().submitInput(new RunInputCommand(runId, idempotencyKey, message))` 向当前 Caller 的活动 Run
提交一段 Steer 文本，返回 SDK 自有的 `RunInputResult`（`inputId`、`RunInputStatus`、接收/应用时间、
iteration、`reasonCode`）。Caller 身份只来自 `SdkCallerProvider`，命令不携带 tenant/principal；
其他 Caller 的 Run 表现为 `RUN_NOT_FOUND`。

- 输入在 Run 的下一个 `BEFORE_ITERATION` 生效：正在执行的 Tool（包括等待委托结果的 Tool）返回之后，
  不在 Tool 执行或模型请求构造中途注入；只进入目标 Run，不广播给 Child。
- 同一幂等键、同一内容的重试返回 `DUPLICATE`（已应用后返回 `APPLIED`），不会重复应用；内容不同则
  `IDEMPOTENCY_CONFLICT`。
- 模型在输入待应用期间给出 Final 时，完成被推迟到模型看过该输入之后；Run 已 `COMPLETING` 或终态时返回
  `REJECTED` 与 `RunInputResult.RUN_NOT_ACCEPTING_INPUT`；已接收但 Run 在应用前停止（取消、失败、超时、
  重启后 recover）的输入结算为 `REJECTED`，`reasonCode` 为 `run-cancelled` 等终态原因。
- 公开事件流以 `inputId` 关联 `run.input.accepted`、`run.input.applied`、`run.input.rejected`，Final
  被推迟时另有 `completion.deferred`（`PENDING_RUN_INPUT`）。

Steer 不是取消：取消继续使用 `agent.runs().handle(runId).cancel()`。

## 父子委托（Child Agent）

产品用 `ChildAgentSpec`（id、面向父模型的描述、instructions、可选 `ProductRunProfileRef`、Tool 白名单）经
`HaifaAgentBuilder.childAgent(...)` 注册 Child，并在 `ProductProfile.allowedChildAgents`（或 `withAllowedChildAgents`）
声明父 Run 可委托的集合。此时 Runtime 向父模型暴露唯一的 `task` Tool；同一响应中的多个 `task` 调用各创建一个普通
Child Run 并行执行，Tool 在 Child 终态后返回 Child Run ID、Runtime 终态、摘要、Child 自身 Usage 与 Artifact 引用。

- Child 模型：引用的 run profile 的模型；未引用时继承父 Run 冻结的模型、预算与限制。
- Child 能力 = Child 白名单 ∩ 父 Run 可用 Tool；构建时白名单越界、未注册 Child 或未注册/不可委托的 run profile 以
  `CHILD_AGENT_TOOL_UNAVAILABLE` / `CHILD_AGENT_UNAVAILABLE` / `CHILD_RUN_PROFILE_UNAVAILABLE` fail closed。
- 深度固定为 1：Child 看不到 `task`。Child 不召回、不写入长期 Memory。
- 并发受父 Run `maxParallelChildren` 与进程上限（默认 3，`maxConcurrentChildRuns`）约束，超出部分按顺序排队，
  排队项在父 Run 停止时直接丢弃；每个 Child 使用自身 profile 的 `maxWallTimeMillis`。
- 等待 Child 不计入父 Run idle，但计入父 Run wall time。Child 需要审批时父 Run 保持等待，审批目标是 Child Run：
  `runs().pendingInteraction(childRunId)` / `runs().respond(...)`。
- 父 Run 取消、超时或恢复会终止其 Child；恢复时未完成 Child 按 interrupted 结算，不自动重放。
- 查询：`runs().children(parentRunId)` 返回 `ChildRunView`（ID、状态、objective、起止时间、usage）；父事件流包含
  `child.run.started` 与 `child.run.completed|failed|cancelled|timed-out`，Child 自身事件用 `runs().events(childRunId, ...)`。
- 父 usage 只记 `childRuns` 计数，不并入 Child token；Child Session 不出现在 Conversation 列表中，只能经父 Run
  （`children` → `view(childRunId).sessionId()`）找到。

## 进程内 Prompt Diagnostics

`agent.runs().promptDiagnostics(runId)` 从 Runtime 实际 `ContextReport` 读取脱敏事实：最终顺序、component
ID、layer/role、version、SHA-256 digest、token estimate 和来源类别。它不返回 Prompt、用户消息、
Memory 或 Tool 正文。查询先沿用当前 Caller 的 Run 授权；未授权、尚未构建 Context、或进程重启后
统一返回 `PROMPT_DIAGNOSTICS_UNAVAILABLE`。该能力没有数据库 Migration、Checkpoint 字段或跨重启
兼容承诺。

## 边界

- SDK 不再拥有 Policy 语义，也不自动批准受信 Skill 脚本：`defaultSdkPolicyRules()` 与
  `TrustedSkillScriptPublicToolPolicy` 均已删除，注册 Skill 不再隐式改变 Tool approval 行为
  （`HAIFA-ADR-020` 已 supersede）。标准规则由 Policy 模块的 `PolicyPresets.standardApproval()` 提供，
  Starter 显式选择该 preset；配置了 Tool 却缺少 `policy` 组件时由 Runtime 明确失败。由于 grant 已不再
  影响审批，`SkillPlatformContribution` 直接拒绝携带 `scriptExecutionGrants` 的 `SkillTrustSnapshot`，
  而不是静默忽略；需要自动批准的产品必须改为显式 Policy 规则。
- 公共 API 不暴露 `RuntimeCoreBuilder`、Runtime Core 内部 bootstrap 类型、SQLite/MyBatis、
  Spring、Provider Client、`Path`、Connection 或 Credential 明文。
- Caller 的 Tenant/Principal 来自可信 `SdkCallerProvider`，不从 Conversation 命令正文接收。
- 诊断只包含逻辑产品、组件标识和非机密装配事实，不包含 Prompt、Memory、Tool 正文、
  Provider 原始配置或绝对路径。
- SDK 不依赖任何产品 Application。Coding、Personal、Document 等上层产品用各自
  `ProductProfile` 选择不同模块实现，但都复用同一 Runtime 状态机和 SDK 装配器。

## 政策、扩展与错误

SDK 的 Run Snapshot/Result 直接暴露 Core `AgentError` 的类型化 code、默认安全 message、
category、retryability、details 和 diagnosticId；调用方无需解析字符串，也不会收到 Runtime
Core 或 Provider 异常。同步请求失败属于 `RuntimeApiErrorCode`，异步 Run 失败属于
`AgentErrorCode`。

应用可通过 `HaifaAgentBuilder.modelRetry(maxAttempts, initialDelay, maxDelay, backoffMultiplier, jitterRatio)`
配置模型 I/O 的有界重试。该配置只影响 Runtime 对可安全重放错误的物理 Attempt；认证、无效请求、
上下文过长、已产生部分输出和取消仍由 Runtime 硬拒绝重试，Provider/Model Binding 也不会隐式切换。
Run Event Feed 使用 `ModelAttemptLifecycle` 暴露逻辑请求、Attempt、等待和耗尽的脱敏稳定视图。

- Memory 治理（人工审查、候选与查询边界）由 `MemoryPlatformContribution` 拥有，Artifact 配额/Media
  Type/本地容量门禁由 `ArtifactPlatformContribution` 拥有；Execution 约束由 `policy` 规则与 `approval`
  验证表达。Profile 不再承载这些策略，本阶段仍不允许关闭 Memory Candidate 人工审查。
- Model、Tool Platform、Skill、Context、Memory、Artifact、Policy、Approval 和 Credential 均通过显式
  typed 组件注册。MCP Tool 由 Integration 直接写入统一 Tool Catalog，不再是独立 SDK
  Capability，也不存在第二条 MCP 执行通道。
- 应用级 Java Tool 通过 `HaifaAgentBuilder.tool(JavaTool)` 逐个注册；SDK 在构建时把它们转换为
  `ToolDefinition` 与 `ToolProvider`，注册进统一 Catalog 并只冻结一次，应用无需理解 Catalog digest
  与 frozen binding，也不需要构造 Tool Platform Contribution。
- 产品可通过 `publicToolPolicyDecorator` 对 Runtime 已选定的公共 Tool Policy 做有界装饰；装饰器
  必须为自己拥有的精确动作生成 request-bound Decision，并把其它动作委托给既有 Policy，不得建立
  第二条 Tool 执行通道。
- `HaifaAgentException` 及 `ConversationException` 对外只暴露安全的 `code`、`operation` 和
  `correlation`。Conversation Adapter、SQLite/Runtime 底层异常和输入正文不会进入公共错误消息。
- `HaifaAgent.memories()` 暴露受 Product Profile、可信 `SdkCaller` 与权限约束的产品级
  propose/revise/approve/reject/invalidate/list API；调用命令不能注入 Tenant、Principal 或 Reviewer。
- `HaifaAgent.memory()` 与 `HaifaAgent.artifacts()` 只在显式装配了对应 typed 组件
  时返回应用服务；SQLite Product Components 已提供 Memory 与 Artifact 的单机持久化实现基线。

当前开发范围由 `docs/20-agent-sdk-product-session-memory-foundation.md` 定义。
