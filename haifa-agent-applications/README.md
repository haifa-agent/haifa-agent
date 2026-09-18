# Haifa Agent Applications

面向具体 Agent 产品的高层应用适配层。这里可以组合 Kernel、Context、Execution 与 Runtime API，但不向底层模块反向泄漏产品概念或具体 Provider。

- `haifa-agent-coding-agent`：Project/Workspace Coding Agent 产品服务，拥有 Session、Queue、Policy 与
  MyBatis/SQLite 产品持久化，不拥有终端；
- `haifa-agent-coding-terminal`：严格映射评审版低保真原型的 tui4j 单列交互层，只通过
  `CodingSessionClient` 消费产品事实，不装配 Runtime、SQLite、Sandbox 或可执行发行包；
- `haifa-agent-cli`：最高层生产装配与唯一 shaded 可执行制品，复用同一个 Runtime、Project、
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
