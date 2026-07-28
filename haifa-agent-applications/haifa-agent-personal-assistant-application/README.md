# Haifa Personal Assistant Application

Personal Assistant 的纯 Java 产品应用层。它只通过 Phase 20 SDK、Conversation Service 和公共
Runtime 视图实现用例，不依赖 Spring、SQLite 实现、HTTP DTO 或 Controller。

本模块负责：

- Conversation start/list/search/get/turns/submit/rename/archive/unarchive；
- Run 查询、取消、最终结果、权威 Usage 与安全 Activity；
- Interaction 查询与响应；
- Memory Candidate review 和 Memory invalidate；
- Personal Product Profile；
- 一个确定性产品 Tool、版本化内置 Skill、可信只读本地 Skill Source；
- 显式本地 MCP connect/discover/allowlist；
- Tool、Skill、MCP 统一冻结到一个 Tool Catalog，并进入同一 Runtime Tool Pipeline。

本模块不负责 HTTP、Spring 装配、SQLite 初始化、Web 页面，也不创建 Personal 专用
Contract、Store、Starter、Tools 或 Skills 子工程。

验证：

```powershell
.\mvnw.cmd -pl :haifa-agent-personal-assistant-application -am test
```
