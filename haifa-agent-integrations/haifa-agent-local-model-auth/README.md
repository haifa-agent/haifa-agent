# Haifa Agent Local Model Authentication

为个人电脑上的 Haifa 产品提供共享的本机模型认证边界：版本化 `auth.json`、`env://` 与
`model-auth://` 凭据解析、外部登录方法注册、登录 Attempt 协调和进程内 Token Refresh single-flight。

本模块只依赖 Common、Model API、Jackson 与 JDK。它不依赖 Coding Agent、Personal Assistant、Spring、Runtime、
SQLite 或任何 UI。产品只在最高层装配受信的登录方法与 Client Registration；普通配置不能注入任意 OAuth
Endpoint、Scope、Header 或实现类。

产品通过 `LocalModelAuthenticationService` 执行列出连接、保存 API Key、启动/查询/取消外部登录和退出登录；
该 Service 是唯一公开写入口，并向模型 Adapter 暴露同一 `CredentialResolver`。接入未来第二个外部登录时，
新增 `ExternalLoginMethod` 并在产品 Composition Root 注册，不修改 Store、Coordinator 或产品 UI 状态机。

`auth.json` 是当前用户专属的明文本机存储，不提供静态加密。权限或 ACL、文件锁、严格 Schema、原子替换
任一检查失败时均拒绝使用。Secret 不得进入日志、异常、公共 View、Runtime SQLite、JSONL 或 Workspace。
Browser 登录在 `WAITING_USER` 阶段通过一次性 `take` 通道向最高层 UI 提供完整授权 URL，供自动打开失败
或窗口不可见时复制；该值不进入通用 Attempt Snapshot，读取后立即清除，禁止写入日志或任何持久化载荷。
回调页收到 authorization code 后只提示“授权已收到”，不会提前宣称登录完成；Attempt 随后进入
`EXCHANGING`，换取并校验 Token 后由 Coordinator 进入 `STORING`，只有 `auth.json` 原子写入成功才进入
`SUCCEEDED`。共享边界通过 JDK System Logger 记录 Attempt ID、Method、Mode、阶段和稳定 reason code；
Codex Token 客户端额外记录安全的 HTTP 状态与 retryable 标记，但绝不记录授权 URL、code、Token、账户 ID
或供应商响应正文。Store 写入失败统一投影为 `AUTH_STORE_FAILED`，避免被折叠成无法定位的通用失败。

验证：

```bash
./mvnw -pl :haifa-agent-local-model-auth -am test
```
