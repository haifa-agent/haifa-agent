# Haifa Agent Local Model Auth Codex Local Compatibility

为 Haifa Agent 产品提供 ChatGPT / OpenAI Codex 个人订阅登录的本地非公开兼容驱动。

## 模块职责与边界

- 实现 `ExternalLoginMethod`（方法 ID `openai-codex`），提供浏览器 PKCE 与设备码（Device Code）两种授权流程；
- 负责向 OpenAI OAuth 端点安全换取与刷新 Access Token / Refresh Token，并解析 Account ID；
- 不维护自身持久化，统一将标准 `StoredExternalCredential` 交由 `haifa-agent-local-model-auth` 模块存储与调度；
- 仅作为外围驱动被最高层 Composition Root（如 `haifa-agent-cli`、`haifa-agent-personal-assistant-server`）显式装配，绝不被通用模型适配器反向依赖。

## 架构约束与隔离

- 本模块只依赖 `haifa-agent-common`、`haifa-agent-model-api`、`haifa-agent-local-model-auth`、Jackson 与 JDK；
- 严禁依赖任何其他厂商驱动模块（如 `antigravity`）；
- 严禁依赖产品层、Runtime、Spring 或 SQLite；
- 所有网络通信与响应解析均设有严格大小限制与 JSON 严格校验；任何异常与日志均禁止输出 Token、Secret 或 Prompt。

## 验证

```bash
./mvnw -pl :haifa-agent-local-model-auth-codex-local-compat -am test
```
