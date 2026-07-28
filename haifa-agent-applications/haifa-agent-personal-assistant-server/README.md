# Haifa Personal Assistant Server

Personal Assistant 的本地 Spring Boot WebFlux 交付模块。默认只监听
`127.0.0.1:20001`，本地确定性 MCP Stub 使用 `127.0.0.1:20002`（也可显式配置为更高端口）；
端口冲突直接启动失败，
不会自动换端口。

Server 负责：

- 显式装配 Product Profile、Model、SQLite、Policy、Memory、Tool、Skill 与 MCP；
- `/api/v1` 版本化 HTTP DTO、显式 Mapper、稳定安全错误；
- Reactor Netty / Spring WebFlux HTTP；
- `Flux<ServerSentEvent<?>>` Run 流，含 heartbeat、bounded overflow、断连清理和安全事件；
- 固定可信 Caller、Host/Origin/CSRF、请求体和安全响应头；
- Actuator liveness/readiness；
- executable Spring Boot JAR 和最小静态占位页。

生产默认使用远程 OpenAI-compatible Model。离线确定性 Model 必须同时显式设置：

```powershell
$env:HAIFA_PERSONAL_MODEL_MODE='deterministic'
$env:HAIFA_PERSONAL_ALLOW_DETERMINISTIC='true'
```

启动还必须提供可持久恢复的 32 字节 AES Key（Base64），不得记录该值：

```powershell
$env:HAIFA_PERSONAL_CONTINUATION_KEY='<base64-aes-256-key>'
java -jar .\target\haifa-agent-personal-assistant-server-0.1.0-SNAPSHOT.jar
```

OpenAPI 位于 `/api/v1/openapi.json`。默认端口及健康检查：

```text
http://127.0.0.1:20001/
http://127.0.0.1:20001/actuator/health
```
