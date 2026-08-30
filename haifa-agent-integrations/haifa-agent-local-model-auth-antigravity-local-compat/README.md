# Haifa Agent Local Model Auth Antigravity Local Compatibility

为 Haifa Agent 产品提供 Google Antigravity / CloudCode 个人账号 OAuth 登录与 Project 发现的本地非公开兼容驱动。

## 模块职责与边界

- 实现 `ExternalLoginMethod`（方法 ID `google-antigravity`），提供浏览器 PKCE 授权流程；
- 仅在 `HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST=true` 且 Client ID/Secret 由环境注入时启用注册；
- 支持安全的 CloudCode Project 发现与配额查询，缺失 Project 时默认返回 `AUTH_ONBOARDING_CONFIRMATION_REQUIRED`，只有显式配置 `HAIFA_ANTIGRAVITY_ALLOW_ONBOARDING=true` 时才允许自动开通；
- 维护内存级 `AntigravityProjectRegistry`，同一 Token 签发版本仅恢复一次 Project 投影，Project ID 不落地写入 `auth.json`；
- 仅作为外围驱动被最高层 Composition Root（如 `haifa-agent-cli`、`haifa-agent-personal-assistant-server`）显式装配，绝不被通用 Gemini 模型适配器反向依赖（Gemini Adapter 仅依赖 `AntigravityCloudCodeProjectResolver` 窄接口）。

## 架构约束与隔离

- 本模块只依赖 `haifa-agent-common`、`haifa-agent-model-api`、`haifa-agent-local-model-auth`、Jackson 与 JDK；
- 严禁依赖任何其他厂商驱动模块（如 `codex`）；
- 严禁依赖产品层、Runtime、Spring 或 SQLite；
- 所有日志、异常及 `toString()` 严格脱敏，不输出 Client Secret、Refresh Token 或未经脱敏的用户标识。

## 验证

```bash
./mvnw -pl :haifa-agent-local-model-auth-antigravity-local-compat -am test
```
