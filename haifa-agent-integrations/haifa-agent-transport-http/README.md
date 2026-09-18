# Haifa Agent HTTP/SSE Transport

`haifa-agent-transport-http` 是面向外部客户端的框架中立参考 Adapter。它把
`haifa-agent-contract` 的 JSON DTO 显式映射到 `haifa-agent-runtime-api`，并提供可由宿主
HTTP 框架接入的请求、响应和 SSE 会话对象。

## 已实现端点

| 操作 | 路径 |
| --- | --- |
| 启动 Run | `POST /v1/runs` |
| 查询 Run | `GET /v1/runs/{runId}` |
| 恢复 Run | `POST /v1/runs/{runId}/resume` |
| 提交 Steer Input | `POST /v1/runs/{runId}/inputs` |
| 提交 Runtime Command | `POST /v1/runs/{runId}/commands` |
| 查询 Pending Interaction | `GET /v1/runs/{runId}/interactions/pending` |
| 响应 Interaction | `POST /v1/runs/{runId}/interactions/{requestId}/responses` |
| 分页读取事件 | `GET /v1/runs/{runId}/events` |
| 订阅事件 | `GET /v1/runs/{runId}/events/stream` |

## 边界

- 本模块只依赖 Contract、Runtime API、Jackson 和 JDK，不依赖 Runtime Core、SQLite 或 Spring Boot。
- `HttpCallerResolver` 从宿主可信边界提供身份；请求 Body 不能注入 Tenant、Principal 或权限。
- `RunOperationAuthorizer` 对每个操作授权；长连接每次读取前重新授权，撤权后立即关闭。
- Header/Body 的幂等键、`If-Match` 和 URL/Body ID 必须一致；冲突在调用 Runtime 前失败。
- Event Page 与 SSE 使用同一个排他、不透明 Cursor。SSE 的 Runtime 回调只向有界队列写完整帧，
  不等待网络；慢消费者、序列化错误、撤权和正常断开都会幂等释放订阅。
- Problem Details 只返回稳定错误码、服务端 correlation ID 和安全详情，不返回堆栈、SQL、
  主机路径、授权规则或敏感输入。
- 无自动截止时间的 Interaction 响应省略 `expiresAt`；Transport 不生成默认期限。

Run 查询 JSON 中的 `error` 是 `AgentExecutionErrorView`；它与 HTTP Problem 的
`RuntimeApiErrorCode` 分离。HTTP 状态只表达本次请求结果，不会把已持久化的 Model、Tool 或预算
执行错误重写成 `INTERNAL_ERROR`。

本模块不拥有 Socket、端口、TLS、Cookie/Bearer 解析、IAM、生产 Server、UI 或产品审批流程。
宿主应用负责把真实 HTTP 框架请求映射为 `HttpTransportRequest`，并只在 SSE 帧成功写出后调用
`acknowledgeWritten`。
