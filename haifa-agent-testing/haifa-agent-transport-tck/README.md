# Haifa Agent Transport TCK

Reactor 末端、不可部署的 Transport-neutral Contract Test Kit。它通过 `TransportTestDriver` 描述
HTTP 请求/响应和事件流，不依赖 Spring、Runtime Core、SQLite 或具体服务器类型。

具体 Adapter 的测试装配可以在测试作用域依赖 Runtime Core 和 Store，并以同一 Driver 验证：

- API Version、Content-Type、Payload 上限和稳定 Problem Details；
- Trusted Caller、逐操作授权、防枚举和权限撤销；
- Header/Body Idempotency、`If-Match` 与 URL/Body Identity；
- Event Page/SSE 共用排他 Cursor、重连、Heartbeat、背压和资源释放；
- 安全 Fixture 以及 Coding、Document、Enterprise 测试场景。

该模块不是生产 Server、认证实现、UI 或产品业务流程。
