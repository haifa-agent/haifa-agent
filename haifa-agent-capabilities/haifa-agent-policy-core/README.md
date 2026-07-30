# Haifa Agent Policy Core

`haifa-agent-policy-core` 是 Policy API 的纯 Java 默认实现。它负责确定性策略计算、Approval
目标与审批权限验证、受限 Approval Grant 匹配、Project Trust 校验，以及进程内 Store。

本模块回答的是：

> 在调用方已经提供可信主体、当前上下文、具体动作、目标资源和风险事实后，应当允许、询问还是拒绝；
> 已有的审批或项目信任是否仍能精确覆盖当前动作。

它不是 IAM、RBAC、工作流引擎或 Sandbox，也不拥有 Run、Interaction、Tool、Execution、数据库
和产品 UI。完整设计见
[Policy、Permission、Approval、安全与信任](../../docs/09-policy-permission-approval-security-and-trust.md)
和 [HAIFA-ADR-009](../../docs/adr/HAIFA-ADR-009-policy-module-and-approval-boundary.md)。

## 模块边界

编译期依赖方向为：

```text
haifa-agent-core
       ^
       |
haifa-agent-policy-api
       ^
       |
haifa-agent-policy-core
```

`policy-core` 只依赖 `haifa-agent-policy-api`。架构测试禁止本模块依赖 Runtime、Tool、Execution、
Store、Application、Spring、JPA、Jackson、MyBatis 和 JDBC。

Policy 相关职责按模块划分如下：

| 模块 | 职责 |
| --- | --- |
| `haifa-agent-policy-api` | 定义 Request、Rule、Snapshot、Decision、Approval、Grant、Trust、验证 SPI 和 Store Port |
| `haifa-agent-policy-core` | 提供上述契约的确定性默认算法和进程内实现 |
| `haifa-agent-runtime-core` | 负责 Tool Pipeline、Interaction 生命周期、等待、响应、恢复和授权证据写入 |
| `haifa-agent-execution-core` | 在真正执行前重新验证绑定的 Decision 和 Approval Evidence |
| `haifa-agent-store-sqlite` | 实现 Policy、Approval、Grant、Trust 的权威持久化 Port |
| Application / Product | 组装 Policy Snapshot、可信身份、产品规则、验证 Provider、审批呈现和业务流程 |
| Tool / Workspace / Credential / Sandbox | 执行各自不可被 Policy 或 Approval 扩大的硬约束 |

这里的核心原则是：

```text
Permission / Capability 决定能力上限
Policy 决定当前动作是 ALLOW、ASK 还是 DENY
Approval 为某个 ASK 提供受验证的授权证据
Execution / Sandbox 在最终边界强制执行
```

Approval 不能新增未装配的 Tool、扩大 Workspace 权限、绕过 Credential Scope，也不能把
`DENY` 改成 `ALLOW`。

## 概念与术语

### Policy Request

`PolicyRequest` 是一次决策的固定字段输入，由以下五部分组成：

| 概念 | 主要字段 | 含义 |
| --- | --- | --- |
| `PolicySubject` | tenant、principal、productId | 谁以哪个产品身份发起动作 |
| `PolicyContext` | project、session、run、attempt、approvalMode、trust、security digest | 动作发生在哪个可信上下文 |
| `PolicyAction` | capability、operation | 要使用什么能力执行什么操作 |
| `PolicyResource` | type、ref、digest、safe summary | 动作指向的资源及其稳定摘要 |
| `PolicyRisk` | level、side effects、credential、network summary | 调用方声明并冻结的风险事实 |

风险等级为 `LOW / MEDIUM / HIGH / CRITICAL`。当前固定副作用为：

- `FILE_WRITE`
- `PROCESS_EXECUTION`
- `NETWORK_ACCESS`
- `CREDENTIAL_USE`
- `EXTERNAL_SYSTEM_MUTATION`

Policy Core 不从自由文本 Prompt 推断风险，也不执行脚本化条件。Tool、Execution 或产品 Adapter
必须先把自己的领域事实转换为固定字段 `PolicyRequest`。

### Policy Rule

`PolicyRule` 包含：

- 稳定的 `PolicyRuleRef`；
- 来源 `SYSTEM / MANAGED / USER / PROJECT`；
- 数值优先级；
- 固定字段 `PolicyRuleMatcher`；
- Effect、可选 Challenge、原因码和安全说明。

Matcher 当前支持精确匹配：

- tenant、product、project、session；
- capability、operation、resource type；
- 最低风险等级；
- 必须同时出现的副作用集合。

空字段表示不限制该维度。`minimumRisk` 使用等级下界；`requiredSideEffects` 要求请求包含规则声明的
全部副作用。

Rule Source 主要表达来源和审计语义，不自动形成 `SYSTEM > MANAGED > USER > PROJECT` 的优先级。
冲突结果由 Effect 和显式 priority 决定。唯一额外门禁是：`PROJECT` 来源的 `ALLOW` 在请求没有
`ProjectTrustRef` 时不参与匹配。

### Policy Snapshot

`PolicySnapshot` 是一次确定的策略集合，包含：

- Snapshot 引用；
- 显式规则和可选默认规则；
- `ApprovalMode`；
- Product Profile 引用；
- 可选 Project Trust 引用；
- 内容摘要和创建时间。

Application 负责构造、冻结并持久化 Snapshot。Core 不读取 YAML、环境变量、数据库配置或用户偏好，
也不负责策略热更新。

`ApprovalMode` 有 `ASK / AUTO / DENY` 三种，但 `DefaultPolicyDecisionService` 不把它当作全局开关。
产品必须在组装规则或 Adapter 时明确解释该模式。例如 Coding Agent 把有副作用动作映射成
ASK、ALLOW 或 DENY；Policy Core 只计算已经组装好的规则。

### Policy Decision

公共 Effect 只有：

| Effect | 含义 |
| --- | --- |
| `ALLOW` | Policy 层允许继续，但后续 Capability、Credential、Execution 和 Sandbox 仍可拒绝 |
| `ASK` | 必须满足 Decision 携带的 Challenge |
| `DENY` | 当前动作被拒绝，Approval Evidence 和 Grant 都不能覆盖 |

`ASK` 必须携带以下 Challenge 之一：

- `APPROVAL`：需要确认当前能力调用；
- `REAUTHENTICATE`：需要产品验证新鲜身份或更强认证。

重新认证不是第四种 Effect，而是 `ASK + REAUTHENTICATE`。

`PolicyDecision` 会保存原始 `PolicyRequest`、稳定 `requestDigest`、Effect、Challenge、原因码、
安全说明、Snapshot 引用、命中规则和决策时间。执行边界可以据此拒绝未知 Decision、未绑定
Decision、主体漂移、Run 漂移或资源摘要漂移。

### Approval

Approval 是对一个 `ASK` Challenge 的响应，不是长期权限本身。

`ApprovalRequestContext` 绑定：

- 来源 `PolicyDecisionId`；
- Approval 语义；
- 允许选择的复用范围；
- Requester；
- 精确 `ApprovalTargetRef`；
- 可选 Authority Requirement；
- 创建时间、到期时间和外部关联引用。

`ApprovalTargetRef` 使用 `targetType + targetId + targetVersion + operation + targetDigest`
标识审批目标，并只携带可安全展示的摘要。产品 Adapter 可以在此基础上绑定 Tool Call ID、冻结
Tool coordinate、definition hash、arguments digest 和 principal scope，防止用户批准后目标被替换。

### Approval Semantics

Approval 分为两类语义：

| 语义 | 用途 | Authority | 可复用性 |
| --- | --- | --- | --- |
| `CAPABILITY_CONFIRMATION` | 用户确认 Agent 可以执行某项能力 | 无外部要求时默认同 tenant、同 principal | 可形成受限 Grant |
| `BUSINESS_AUTHORIZATION` | 经理、财务、法务等业务角色批准某笔业务 | 必须指定产品提供的 Authority Provider | 仅允许 `ONCE`，不能形成 Grant |

Policy Core 不理解“直属经理”“金额超过十万”或“法务会签”等组织业务含义。产品把这类要求表示为
稳定的 `ApprovalAuthorityRequirementRef`，再通过 `ApprovalAuthorityVerifier` 接入实际 IAM 或
业务系统。

### Approval Evidence

`PolicyAuthorizationEvidence` 表示某个精确 Decision 的 Challenge 已被可信 Validator 和 Verifier
满足。它绑定 Decision、request digest、Requester、Responder、批准时间和到期时间。

Evidence：

- 不扩大 Capability；
- 不覆盖 `DENY`；
- 不自动变成可复用 Grant；
- 只对绑定的 Decision 和有效时间窗口成立。

Policy Core 提供进程内 Evidence Store；Runtime 负责在 Interaction 响应通过验证后写入 Evidence，
Execution 边界负责消费和复核。

### Approval Grant

`ApprovalGrant` 是从已完成的能力确认中显式派生的受限复用授权。它记录来源 Decision、Approval
Request/Response、可信 Responder、主体、动作、目标、到期时间、状态和乐观锁版本。

复用范围为：

| Scope | 匹配要求 | 生命周期 |
| --- | --- | --- |
| `ONCE` | Subject、Action 和完整 Target 精确一致 | 首次成功授权时原子消费为 `CONSUMED` |
| `SESSION` | 通用目标摘要匹配，且 Session Ref 一致 | 到期或撤销前可复用 |
| `PROJECT` | 通用目标摘要和 Project Ref 一致，并重新验证 Project Trust 与安全配置 | 到期、撤销、信任失效或配置漂移时失效 |

所有 Scope 都先要求：

- Grant 处于 `ACTIVE` 且未过期；
- `PolicySubject` 和 `PolicyAction` 完全一致；
- Target Type、Operation 和 Target Digest 一致。

`BUSINESS_AUTHORIZATION` 在契约层禁止构造 `ApprovalGrant`，避免把一次业务审批错误提升为长期能力。

### Project Trust

`ProjectTrust` 不是“信任一个路径字符串”。它绑定：

- tenant 和 principal；
- product-owned project reference；
- canonical project identity；
- trusted root identity；
- security configuration digest；
- product profile；
- 状态、确认时间、到期时间和版本。

调用方在每次 Project Grant 授权时提供当前 `ProjectTrustExpectation`。只有所有字段仍精确一致、
Trust 仍为 `TRUSTED` 且未过期时，Project Grant 才有效。项目移动、根身份变化、安全配置变化、
Profile 变化、撤销或过期都会 fail closed。

## 确定性决策规则

`DefaultPolicyDecisionService` 的计算过程为：

1. 使用固定 Matcher 过滤 Snapshot 中的显式规则；
2. `PROJECT + ALLOW` 只有在请求携带 Project Trust 引用时才可参与；
3. 在所有命中规则中选择最严格结果；
4. 没有显式规则命中时，尝试匹配可选默认规则；
5. 仍无匹配时返回 `DENY / POLICY_NO_MATCH`；
6. 将完整 Request 和计算得到的 `requestDigest` 写入 Decision。

规则排序固定为：

```text
Effect: DENY > ASK > ALLOW
    -> priority 数值从高到低
    -> ruleId 字典序
    -> ruleVersion 字典序
```

因此结果不依赖规则注册顺序。`ASK` 与 `ALLOW` 同时命中时选择 `ASK`，任何命中的 `DENY`
都会压过 `ASK` 和 `ALLOW`。

Decision Service 只计算并返回 Decision，不负责保存。调用方必须通过 `PolicyDecisionStore` 保存，
以便 Runtime、Execution 和审计链通过稳定 Decision Ref 使用同一事实。

## Approval 验证流程

`DefaultApprovalVerificationService` 按固定顺序执行：

```text
ApprovalRequestContext
        |
        v
按 targetType 查找 ApprovalTargetValidator
        |
        v
验证目标仍为 CURRENT
        |
        +-- CAPABILITY_CONFIRMATION 且无外部 Authority
        |       -> LocalCapabilityAuthorityVerifier
        |       -> 要求 Responder 与 Requester 同 tenant、同 principal
        |
        +-- 其他情况
                -> 必须存在 ApprovalAuthorityRequirementRef
                -> 按 providerId 查找 ApprovalAuthorityVerifier
                -> 产品 Provider 验证 Responder 是否有权审批
        |
        v
ApprovalVerification(accepted, reasonCode)
```

安全行为：

- 先验证目标，再验证审批权限；
- Target Validator 缺失、抛异常或返回空值时拒绝；
- Authority Requirement 缺失或 Verifier 不可用时拒绝；
- Authority Verifier 抛异常或返回空值时拒绝；
- Core 只返回安全原因码，不把 Provider 异常细节当作授权依据。

`LocalCapabilityAuthorityVerifier` 只是本地同主体确认，不等同于密码、MFA、系统登录或企业 SSO
重新认证。`REAUTHENTICATE` 的真实强认证必须由产品提供相应 Authority Verifier。

## Grant 与 Trust 授权流程

`DefaultApprovalGrantService.authorize` 的流程为：

1. Store 按 Subject、Action 和 Target Type 返回候选 Grant；
2. `ApprovalGrantMatcher` 校验状态、有效期、主体、动作、目标摘要和 Scope；
3. `PROJECT` Scope 要求调用方同时提供当前 `ProjectTrustExpectation`；
4. 从 `ProjectTrustStore` 读取 Trust，并逐字段匹配当前项目身份和安全配置；
5. `SESSION / PROJECT` 返回有效 Grant；
6. `ONCE` 使用 Store 的条件更新原子消费；
7. 并发消费或撤销产生版本冲突时，继续检查其他候选；没有有效候选则拒绝。

Grant 匹配不会重新计算 Policy，也不会覆盖当前 Policy 的显式 `DENY`。正确的上层调用顺序应先完成
当前 Policy 决策，再按产品规则判断一个 `ASK` 是否允许由既有 Grant 满足。

## 进程内 Store

`InMemoryPolicyStore` 同时实现：

- `PolicySnapshotStore`
- `PolicyDecisionStore`
- `ApprovalGrantStore`
- `ProjectTrustStore`

Snapshot、Decision、Grant 和 Trust 都使用稳定 ID：

- 同一 ID 重复保存完全相同的值是幂等操作；
- 同一 ID 保存不同内容会被拒绝；
- Grant 消费、Grant 撤销和 Trust 撤销使用 expected version；
- `ONCE` Grant 的消费在 Store 临界区内完成，防止同一实例中的重复消费。

`InMemoryPolicyAuthorizationEvidenceStore` 保存 Challenge Satisfaction Evidence，并同样拒绝同一
Decision ID 对应不同内容。

这些实现适合单进程装配和测试，不是跨进程恢复事实源。需要恢复时应注入
`haifa-agent-store-sqlite` 提供的权威 Store。

## 与其它模块的端到端交互

下面是当前 Tool/Execution 主路径。虚线外的生命周期不属于 Policy Core：

```text
Application
  组装可信 Caller、Product Rules、Policy Snapshot、Store 和验证 Provider
        |
        v
Runtime Tool Adapter
  Frozen Tool Binding + Tool Request -> PolicyRequest
        |
        v
DefaultPolicyDecisionService
  计算 ALLOW / ASK / DENY
        |
        v
PolicyDecisionStore
        |
        +-- DENY -> Runtime 拒绝 Tool Call
        |
        +-- ALLOW -> 进入 Credential / Tool Provider / Execution 边界
        |
        +-- ASK -> Runtime 创建持久 Interaction，Run 进入 WAITING_APPROVAL
                         |
                         v
                  Product UI / HTTP / Terminal 收集响应
                         |
                         v
                  ApprovalVerificationService
                    目标验证 + 审批权限验证
                         |
                         v
                  Runtime 保存 Authorization Evidence 并恢复 Run
                         |
                         v
                  Tool Pipeline 重验冻结 Tool、参数摘要、主体和 Decision
                         |
                         v
Execution Core
  重验 Decision、Snapshot、tenant/principal、run、action、resource digest 和 Evidence
        |
        v
ExecutionBroker / Workspace / Credential / Sandbox
  强制执行工作目录、网络、凭据、超时、输出和进程约束
```

### Runtime Core

Runtime 只依赖 `policy-api`，不依赖本模块。它负责：

- 把产品提供的 Policy 实现接入唯一 Tool Pipeline；
- 保存 Decision；
- 创建和持久化 Interaction；
- 管理 `WAITING_APPROVAL`、响应幂等、恢复和超时；
- 保存 Approval Verification 元数据和 Authorization Evidence；
- 在恢复执行前验证 Tool Approval Target 没有漂移。

Policy Core 不维护第二套 Run 或 Interaction 状态机。

### Tool Core 与 Tool Provider

Policy Core 不依赖 Tool 类型。Runtime Adapter 从 `FrozenToolBinding` 和 Tool Request 提取 capability、
operation、resource 和 risk，转换为 `PolicyRequest`。

Tool 自身的风险、Approval Requirement、资源约束和 Credential Requirement 仍由 Tool 契约与
Pipeline 校验。Policy `ALLOW` 不会修复互相矛盾的 Tool 定义，也不会跳过 JSON Schema 校验。

### Execution Core 与 Sandbox

Execution Core 只依赖 `policy-api`。真正启动进程前，它通过稳定 Decision Ref 重新验证：

- Decision 和 Snapshot 存在；
- Decision 携带原始 Request；
- tenant、principal、run、action 和 resource digest 一致；
- `ASK` Decision 存在未过期且 request digest 一致的 Evidence。

Policy 解决“是否应该执行”，Sandbox 解决“技术上允许执行到什么范围”。Host Guarded Provider
只是受控宿主执行，不等同于容器、microVM 或多租户强隔离。

### Workspace 与 Project

Workspace 的 `LIST / STAT / READ / SEARCH / WRITE / DELETE / EXECUTE` 是独立硬权限。Project
产品层负责提供 canonical identity、trusted root identity、当前安全配置摘要和 Product Profile，
Policy Core 只对 `ProjectTrustExpectation` 做精确比较。

### Credential

Credential Broker 负责 Binding、Scope、解析、Lease、脱敏和审计。Policy 可根据
`CREDENTIAL_USE` 或 `credentialRequired` 要求 Approval/Reauthentication，但不会直接解密凭据，
也不会扩大 Credential Scope。

### SQLite

SQLite Adapter 实现 Snapshot、Decision、Approval Request/Response Metadata、Authorization
Evidence、Grant 和 Project Trust 的权威持久化。Policy Core 不依赖 JDBC、MyBatis 或具体 Schema。

### Application 与产品层

Application/Product 必须负责：

- 从可信启动配置组装 Snapshot 和规则；
- 从认证边界提供 tenant、principal 和产品身份；
- 选择 `ASK / AUTO / DENY` 的产品含义；
- 注册 Target Validator 和 Authority Verifier；
- 展示安全摘要并收集用户响应；
- 创建、限制、撤销 Grant 和 Project Trust；
- 实现组织关系、审批路由、待办、意见正文和业务事务。

产品不得绕过 Runtime Tool Pipeline 或 ExecutionBroker 单独执行已经被治理的动作。

## Fail-closed 条件

以下情况必须拒绝或返回未授权：

- 没有匹配规则且没有匹配的默认规则；
- `ASK` 没有 Challenge，或非 `ASK` 携带 Challenge；
- Project 来源的 `ALLOW` 没有 Project Trust 引用；
- Target Validator 或 Authority Verifier 缺失、失败或返回空值；
- Approval Target 已过期、不可用或发生漂移；
- Grant 已过期、撤销、消费、主体不匹配或目标摘要不匹配；
- `ONCE` Grant 并发消费失败；
- Project Trust 缺失、撤销、过期或任一身份/配置字段变化；
- Store 中同一 ID 对应不同内容，或 expected version 冲突。

## 当前实现边界

已实现：

- 固定字段、确定性的 Rule 匹配；
- `DENY > ASK > ALLOW` 合并；
- Request-bound Decision 和稳定 digest；
- 本地同主体能力确认；
- 产品级 Authority/Target 验证 SPI；
- `ONCE / SESSION / PROJECT` Grant 匹配；
- `ONCE` 原子消费、Grant/Trust 撤销和乐观锁；
- Project Trust 精确匹配；
- Policy、Evidence、Grant、Trust 的进程内 Store；
- SQLite 权威 Store 的跨模块接入契约。

当前 Coding Agent 的 Runtime Tool Approval 只开放 `ONCE`。Grant/Project Trust 的 API、Core 和
SQLite Store 已存在，但完整的 Session/Project Grant 管理、“信任此项目”体验和产品撤销入口尚未
完成装配。

不属于本模块：

- 通用 IAM、RBAC/ABAC、角色继承和组织图；
- 动态脚本规则、任意表达式语言和外部规则引擎；
- 多级审批、会签/或签、审批待办和业务状态机；
- Runtime Interaction、HTTP/SSE、Terminal 或 Web UI；
- Tool Catalog、Workspace Permission、Credential Store；
- Sandbox、进程隔离、网络隔离和资源配额；
- 生产级 KMS、Vault、MFA、SSO 或企业目录。

## 关键实现

| 类 | 作用 |
| --- | --- |
| `DefaultPolicyDecisionService` | 固定字段规则匹配和确定性 Decision 计算 |
| `DefaultApprovalVerificationService` | Target-first 的 Approval 验证编排 |
| `LocalCapabilityAuthorityVerifier` | 本地同 tenant、同 principal 验证 |
| `ApprovalGrantMatcher` | Grant 状态、主体、动作、目标与 Scope 匹配 |
| `DefaultApprovalGrantService` | Grant 查找、Project Trust 复核和 `ONCE` 原子消费 |
| `InMemoryPolicyStore` | Snapshot、Decision、Grant、Trust 的进程内 Store |
| `InMemoryPolicyAuthorizationEvidenceStore` | Authorization Evidence 的进程内 Store |

## 验证

在仓库根目录运行：

```powershell
.\mvnw.cmd -pl :haifa-agent-policy-core -am test
```

模块测试覆盖：

- Rule 顺序无关和 `DENY > ASK > ALLOW`；
- 无规则时 fail closed；
- Project `ALLOW` 的 Trust 门禁；
- 固定 Matcher；
- Target/Authority 缺失和异常时 fail closed；
- 本地同主体确认和企业 Authority SPI；
- Grant Scope、到期、撤销、摘要漂移和并发消费；
- Project Trust 配置漂移与撤销；
- 纯 Java 和依赖边界。
