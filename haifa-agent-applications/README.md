# Haifa Agent Applications

面向具体 Agent 产品的高层应用适配层。这里可以组合 Kernel、Context、Execution 与 Runtime API，但不向底层模块反向泄漏产品概念或具体 Provider。

- `haifa-coding-api`：仅依赖 JDK 的实验性 Coding 产品合同，包含共享 schema、Java/TS fixtures、
  有界展示 DTO 和原子 reducer；不属于基础 SDK BOM，也不提供网络 Client；
- `haifa-coding-host`：可复用产品 owner/SQLite 装配、显式可信资源和同源浏览器授权；无 CLI/TUI 依赖；
- `haifa-coding-client`：仅 API/Jackson 的轻量 Java HTTP/SSE Client，不带 engine/SQLite；
- `haifa-coding-browser-client`：基于同一 schema 的浏览器 TS SDK 私有预览；无 Node 或框架运行依赖；
- `haifa-coding-web`：消费打包 SDK 的同源静态 Web，由显式开启浏览器模式的可信 Host 提供；
- `haifa-agent-coding-agent`：Project/Workspace Coding Agent 产品服务，拥有 Session、Queue、Policy 与
  MyBatis/SQLite 产品持久化，不拥有终端；
- `haifa-agent-coding-terminal`：严格映射评审版低保真原型的 tui4j 单列交互层，只通过
  `CodingClient` 接入可选产品终端。默认 native 入口继续使用兼容客户端。终端模块不装配 Runtime、SQLite、Sandbox 或可执行发行包；
- `haifa-agent-cli`：参数/终端/发行兼容入口与唯一 shaded 可执行制品，产品装配委托 Host SDK，复用同一个 Runtime、Project、
  Workspace、Policy、Tool、Execution、Persistence 和 `CodingSessionService`，同时提供 Terminal
  默认入口与兼容的 `-m` one-shot 模式。
- `haifa-agent-personal-assistant-application`：纯 Java Personal 产品用例、Product Profile，以及
  Tool、Skill、MCP 统一 Pipeline 装配；不依赖 Spring 或 SQLite 实现；
- `haifa-agent-personal-assistant-server`：Spring Boot WebFlux、SQLite、版本化 HTTP/SSE DTO、
  OpenAPI 与 executable JAR；只交付后端 API，默认监听 `127.0.0.1:20001`；
- `haifa-agent-runtime-demo`：按 Model-only、Raw Tool、MCP、Skill 能力场景拆分的显式 DeepSeek
  Runtime Core 示例应用；它不属于 Live Test Catalog，运行结果不能替代自动化 Probe 或 E2E；
- `haifa-agent-personal-assistant-web`：独立构建和部署的 React 前端，默认监听
  `127.0.0.1:20000`，由浏览器直接调用 Server。前端历史 Mock/API 假设不是后端代码事实，
  以 Server v1 DTO 和 OpenAPI 为准。
