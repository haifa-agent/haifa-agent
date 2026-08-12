# Haifa Agent

Haifa Agent 是面向 Java 生态的通用 Agent Runtime 与产品开发平台。当前版本为 `0.1.0-SNAPSHOT`，使用 Java 21 与 Maven Wrapper 3.9.15。

## 当前已实现

- Core 领域模型、`AgentRun` 状态机、Runtime API 与异步 AgentLoop；
- Provider-neutral 的 Model、Tool、Skill、Credential、Memory 与 Context 契约；
- OpenAI、DeepSeek、阿里云百炼、火山方舟的 OpenAI-compatible Chat Completions 适配，支持流式输出、Tool Call、最终 usage 和受保护的 reasoning continuation；
- 冻结 Tool Binding、受限 JSON Schema 校验、短生命周期凭据租约与 AES-GCM 本地凭据存储；
- 固定协议 `2025-11-25` 的 MCP Client，支持 Streamable HTTP 与由 `ExecutionBroker` 托管的 stdio；
- 兼容 `SKILL.md` 的 Skill API/Core/Base，支持分层发现、内容寻址冻结、摘要披露、Run 级受控激活和资源按需读取；
- 可冻结的 `web.search` / `web.fetch` Tool：Search 支持 Aliyun、Brave、Tavily，Fetch 当前只支持 Aliyun；
- Project/Workspace 的受控文件访问、变更集、补丁、索引、快照与显式 Artifact 导出；
- ExecutionBroker、Sandbox SPI、受控 Host Provider、macOS Seatbelt/Linux bubblewrap Local Native
  Provider，以及只读 Git 适配；
- SQLite V1～V6 Migration、版本化 Codec、线程绑定 UoW、完整 Runtime Persistence Port、持久
  Interaction/Run Input/Event Journal、事务恢复与故障收敛；
- 纯 Java `haifa-agent-sdk` 高层 Facade、可信 Product Profile、确定性 Capability Contribution
  装配，以及产品中立 Conversation Session；SQLite V5 提供 Conversation metadata、命令幂等绑定，
  V6 提供全人工确认的 Memory Candidate、正式 Memory 与最小只写 Audit，
  revision、单活动 Run 与重启恢复；
- 类型化 `JavaTool<I, O>`、Java record Schema/Codec、单 Tool Catalog 合并，以及默认 DeepSeek V4
  Flash 的纯 Java `haifa-agent-sdk-starter`；Starter 还提供展示元数据、轻量 `chat()` 调用、默认指令
  诊断和不含 Prompt 正文的进程内 Prompt Diagnostics；
- Spring Boot Starter 与自动装配：默认创建单例 `HaifaAgent`、收集 `JavaTool` Bean、生成配置元数据，
  支持有序 Starter Customizer，并在应用关闭时释放 Agent；默认模型仍为 DeepSeek V4 Flash 且关闭
  Thinking，可信宿主可显式注册多 Provider/多模型目录；
- 可运行的 `haifa-agent-sdk-example`，按 Basic、Intermediate、Advanced 分层覆盖 Quickstart、类型化
  Tool、多模型选择、Conversation、Run 观察/控制、可信 Caller 与 SQLite 单机持久化参考装配；该示例
  模块不是发布制品；
- 主仓跟踪但不加入 Reactor 的 `examples/haifa-agent-example`，通过独立 Maven 构建从已安装 BOM/Artifact
  消费 SDK，只保留完整 Pure Java 与 Spring Boot 应用，不维护第二套教学示例；
- 可选的安全 JSONL Transcript 投影，支持 at-least-once 去重、截断诊断、跨进程锁与原子轮转；
- 纯 Java Policy API/Core，支持请求绑定决策、`DENY > ASK > ALLOW`、受限 Approval Grant、
  Project Trust、产品验证 SPI 和内存 Store；SQLite V3 已提供 Snapshot、Decision、Evidence、
  Grant 与 Trust 的权威持久化，Runtime Tool、Coding Agent 与 ExecutionBroker 共享同一 Decision；
- 公共 Contract、持久 Interaction/Steer/Run Event Feed、框架中立 HTTP/JSON + SSE 参考 Adapter，
  以及 Reactor 末端的 Transport TCK；
- Coding Agent 产品模块、严格映射评审原型的 tui4j Terminal 与唯一可执行 CLI；CLI 支持交互
  Terminal、兼容的 one-shot 模式、顶层 `resume` 选择/最近/指定 Session 恢复、最近安全可见历史、
  Session 搜索/重命名/归档/逻辑删除、线性历史压缩、根
  `AGENTS.md` 冻结/reload、受治理的 `!`/`!!` 一次性命令和安全 JSONL 导出，并可显式选择
  `MEMORY`、`SQLITE` 或 `SQLITE_WITH_JSONL`。

尚未实现的能力不应被视为当前行为，包括 Enterprise SDK、生产 Server/Worker/Admin、Skill
Hub/创作与企业管理面、Knowledge、Graph、完整的 Project Trust/Approval 产品体验、分布式
Store/Lease、生产 KMS/Vault、Windows Local Native Adapter、容器或 microVM Sandbox，以及
Session Tree/Fork/Clone、PTY 和后台 Job。

## 当前 Reactor

```text
build-support/
  haifa-agent-bom/
  haifa-agent-spring-bom/
haifa-agent-contract/
haifa-agent-sdk/
haifa-agent-sdk-starter/
haifa-agent-spring/
  haifa-agent-spring-boot-autoconfigure/
  haifa-agent-spring-boot-starter/
haifa-agent-kernel/
  haifa-agent-common/
  haifa-agent-core/
  haifa-agent-runtime-api/
  haifa-agent-context/
  haifa-agent-project/
  haifa-agent-artifact/
  haifa-agent-runtime-core/
haifa-agent-execution/
  haifa-agent-execution-api/
  haifa-agent-sandbox-api/
  haifa-agent-execution-core/
  haifa-agent-sandbox-host/
  haifa-agent-sandbox-local-native/
haifa-agent-capabilities/
  haifa-agent-credential-api/
  haifa-agent-credential-core/
  haifa-agent-tool-api/
  haifa-agent-tool-core/
  haifa-agent-model-api/
  haifa-agent-model-core/
  haifa-agent-memory-api/
  haifa-agent-memory-core/
  haifa-agent-policy-api/
  haifa-agent-policy-core/
  haifa-agent-skill-api/
  haifa-agent-skill-core/
  haifa-agent-skill-base/
haifa-agent-integrations/
  haifa-agent-web/
  haifa-agent-model-openai-compatible/
  haifa-agent-git/
  haifa-agent-mcp/
  haifa-agent-store-sqlite/
  haifa-agent-store-jsonl/
  haifa-agent-transport-http/
haifa-agent-applications/
  haifa-agent-coding-agent/
  haifa-agent-coding-terminal/
  haifa-agent-cli/
  haifa-agent-personal-assistant-application/
  haifa-agent-personal-assistant-server/
  haifa-agent-sdk-example/
  haifa-agent-runtime-demo/
haifa-agent-testing/
  haifa-agent-testkit/
  haifa-agent-test-fixtures/
  haifa-agent-transport-tck/
  haifa-agent-integration-tests/
  haifa-agent-e2e-tests/
```

`examples/haifa-agent-example` 由主仓 Git 管理，但不属于上述 Reactor。它不继承主仓 Parent POM，必须在
匹配版本的 Haifa 制品安装到本地 Maven 仓库后独立验证。

实线表示编译期依赖，箭头从使用方指向被依赖方：

```mermaid
flowchart LR
  CORE[core] --> COMMON[common]
  RAPI[runtime-api] --> CORE
  RCORE[runtime-core] --> RAPI
  RCORE --> MAPI[model-api]
  RCORE --> TAPI[tool-api]
  RCORE --> PAPI[policy-api]
  RCORE --> CAPI[credential-api]
  RCORE --> CTX[context]
  RCORE --> MEMAPI[memory-api]
  RCORE --> MEMCORE[memory-core]
  RCORE --> SKAPI[skill-api]
  CTX --> MAPI
  TAPI --> CAPI
  TCORE[tool-core] --> TAPI
  TCORE --> MAPI
  CCORE[credential-core] --> CAPI
  MCORE[model-core] --> MAPI
  MEMAPI --> CORE
  MEMCORE --> MEMAPI
  PAPI --> CORE
  PCORE[policy-core] --> PAPI
  SKCORE[skill-core] --> SKAPI
  SKBASE[skill-base] --> SKAPI
  SKBASE --> SKCORE
  PROJECT[project] --> CORE
  ARTIFACT[artifact] --> CORE
  EAPI[execution-api] --> PROJECT
  SAPI[sandbox-api] --> EAPI
  ECORE[execution-core] --> EAPI
  ECORE --> SAPI
  SHOST[sandbox-host] --> SAPI
  SLOCAL[sandbox-local-native] --> SAPI
  SLOCAL --> PROJECT
  GIT[git] --> EAPI
  GIT --> SAPI
  GIT --> PROJECT
  MCP[mcp] --> TAPI
  MCP --> CAPI
  MCP --> EAPI
  OAI[model-openai-compatible] --> MAPI
  SQLITE[store-sqlite] --> RCORE
  SQLITE --> PAPI
  JSONL[store-jsonl] --> RCORE
  CONTRACT[contract] --> COMMON
  HTTP[transport-http] --> CONTRACT
  HTTP --> RAPI
  SDK[agent-sdk] --> RCORE
  SDK --> MAPI
  SDK --> TAPI
  SDK --> SKAPI
  STARTER[sdk-starter] --> SDK
  STARTER --> OAI
  SAUTO[spring-boot-autoconfigure] --> STARTER
  SBOOT[spring-boot-starter] --> SAUTO
  SQLITE --> SDK
  PAPP[coding-agent + built-in Web Tool] --> RCORE
  PAPP --> SQLITE
  PAPP --> JSONL
  PAPP --> PROJECT
  PAPP --> TCORE
  PAPP --> CCORE
  PAPP --> PAPI
  PAPP --> PCORE
  PAPP --> SKCORE
  PAPP --> SKBASE
  TERM[coding-terminal] --> PAPP
  TERM --> RAPI
  CLI[cli] --> TERM
  CLI --> PAPP
  CLI --> MCP
  CLI --> SHOST
  CLI --> SLOCAL
  TESTKIT[testkit]
  FIXTURES[test-fixtures]
  TTCK[transport-tck] --> TESTKIT
  TTCK --> CONTRACT
  TTCK --> RAPI
  TTCK --> HTTP
  ITEST[integration-tests] --> TESTKIT
  ITEST --> FIXTURES
  ITEST --> OAI
  RDEMO[runtime-demo] --> RCORE
  RDEMO --> OAI
  RDEMO --> MCP
  E2ETEST[e2e-tests] --> CLI
  E2ETEST --> SQLITE
```

`haifa-agent-testing` 位于 Reactor 末端，只承载测试基础设施。Testkit 与 Test Fixtures 提供公共
编排和安全输入；Integration 与 E2E 模块分别承载确定性跨模块验证和完整产品路径，真实 Provider
窄探针与对应 Adapter 相邻保存。生产模块不得反向依赖测试模块。模块私有 Fixture 继续就近保存在各模块的
`src/test/resources`，只有跨模块共享且可安全进入源码仓库的小型 Fixture 才上移到共享模块。

## 关键边界

- `common`、`core`、`runtime-api`、`context`、`project`、`artifact`、各 Capability API、Execution API 和 Sandbox API 保持纯 Java；
- `AgentRun` 生命周期只由 Core 的命名领域行为决定，Runtime 不维护第二份状态转换表；
- Runtime 只依赖 API/SPI，不依赖具体模型、MCP 或 Sandbox Provider；
- Policy API/Core 只提供产品无关的决策、Approval 语义、Grant/Trust 和验证 SPI；Runtime
  Interaction 与 SQLite V3 Adapter 已接入，首次目录信任、审批路由、待办和企业业务流程仍由产品层实现；
- `haifa-agent-contract` 与 `haifa-agent-transport-http` 只提供外部 DTO 和框架中立 HTTP/SSE
  映射，不拥有 Socket、TLS、IAM、生产 Server 或产品审批流程；
- SQLite 是恢复的唯一事实源；JSONL 只是可删除、可重建的安全投影，不能反向恢复 Runtime；
- 持久文件在 POSIX 使用目录 `0700`/文件 `0600`，在 Windows 使用当前用户独占 ACL；权限无法验证时 fail closed；
- 凭据明文只在短生命周期 `CredentialLease` 中使用，不进入 Prompt、Tool 参数、Checkpoint、Trace 或 Workspace；
- 对模型暴露的 Tool alias 与内部精确坐标分离；Run 创建后冻结 Tool Binding 与模型快照；
- Definition/Profile 允许的 Skill 在 Run 创建时冻结为精确内容摘要；模型先看到元数据摘要，只有通过统一 Tool Pipeline 激活后才注入 `SKILL` 层内容；
- Skill 是不可信的方法与资源包，不扩大冻结 Tool 集，不直接执行脚本、读取凭据或访问网络；
- CLI 可从可信配置装配绝对路径的本地用户 Skill 目录，但目录内容仍须经过解析门禁和显式 alias allowlist；
- CLI 的 Terminal 与 one-shot 命令复用同一生产装配；macOS、Linux、Windows 默认均冻结为
  `host-guarded + network allow + shell auto` 的可信本地开发基线，保留命令产生的真实可用路径，并
  支持编译、测试和同一命令内的临时 loopback Server。它使用普通宿主网络能力，不宣称外部网络隔离。
- macOS/Linux 可显式选择 `local-native + network deny` 严格模式；Windows 当前不提供同等级严格隔离，
  也不会伪装成 Local Native。
- Local Native 只声明已预检的 Workspace 文件策略、网络关闭和进程树收敛；它仍共享宿主
  Kernel，不声明 CPU、内存、磁盘、PID、Container、VM 或多租户强隔离。
- Host Sandbox 是默认的可信本地受控执行，不等同于网络、CPU、内存或文件系统强隔离。长期 Server、
  后台任务和 PTY 仍未作为三端产品入口提供。

## 构建与验证

Linux/macOS：

```bash
./build-support/scripts/invoke-haifa-maven.sh --layer L1 -- \
  -pl :haifa-agent-runtime-core -am \
  -Dtest=RuntimeCoreTest -Dsurefire.failIfNoSpecifiedTests=false test
./build-support/scripts/invoke-haifa-maven.sh --layer L2 -- \
  -pl :haifa-agent-runtime-core -am test
./build-support/scripts/invoke-haifa-maven.sh --layer L2 -- test
./build-support/scripts/invoke-haifa-maven.sh --layer L0 -- spotless:apply
./build-support/scripts/invoke-haifa-maven.sh --layer L3 -- -Pci-fast clean verify
./build-support/scripts/invoke-haifa-maven.sh --layer L3 -- -Pci-integration-only verify
./build-support/scripts/invoke-haifa-maven.sh --layer L3 -- \
  -pl :haifa-agent-cli -am -Prelease-artifacts verify

# 唯一可执行制品；无 -m 时默认启动 tui4j Terminal
./mvnw -pl :haifa-agent-cli -am package
java -jar ./haifa-agent-applications/haifa-agent-cli/target/haifa-agent-cli-0.1.0-SNAPSHOT.jar --help
```

Windows PowerShell：

```powershell
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L1 -- `
  -pl :haifa-agent-runtime-core -am `
  '-Dtest=RuntimeCoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L2 -- `
  -pl :haifa-agent-runtime-core -am test
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L2 -- test
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L0 -- spotless:apply
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L3 -- -Pci-fast clean verify
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L3 -- -Pci-integration-only verify
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L3 -- `
  -pl :haifa-agent-cli -am -Prelease-artifacts verify

# 唯一可执行制品；无 -m 时默认启动 tui4j Terminal
.\mvnw.cmd -pl :haifa-agent-cli -am package
java -jar .\haifa-agent-applications\haifa-agent-cli\target\haifa-agent-cli-0.1.0-SNAPSHOT.jar --help
```

精确测试使用 L1 串行反馈；模块完整测试和全仓增量测试使用 L2 固定四线程；最终门禁使用 L3。
统一入口会记录脱敏指标并避免按 CPU 核数无界放大并发。直接调用 Wrapper 仍受支持，但不作为日常
全仓测试的推荐入口。

普通开发与 CI 不运行真实模型、外部 MCP 或 Web Provider 服务。DeepSeek 与 Web Live Test 都必须使用各自的显式开关和独立凭据，访问外部服务并可能产生费用。

## 架构文档

- [架构基线](docs/architecture-baseline.md)
- [当前模块与依赖](docs/02-repository-modules-and-dependencies.md)
- [持久化与存储架构（已实现基线）](docs/08-persistence-and-storage-architecture.md)
- [SDK 基建与多产品演进路线](docs/roadmap/sdk-foundation-and-multi-product-roadmap.md)
- [Agent 产品文档索引](docs/products/README.md)
- [Pi Coding Agent 功能差距与迭代路线图（产品 PRD）](docs/prd/pi-coding-agent-capability-gap-and-iteration-roadmap.md)
