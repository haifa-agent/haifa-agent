# Haifa Agent Local Model Authentication

为个人电脑上的 Haifa 产品提供共享的本机模型认证边界：版本化 `auth.json`、`env://` 与
`model-auth://` 凭据解析、外部登录方法注册、登录 Attempt 协调和进程内 Token Refresh single-flight。

本模块只依赖 Model API、Jackson 与 JDK。它不依赖 Coding Agent、Personal Assistant、Spring、Runtime、
SQLite 或任何 UI。产品只在最高层装配受信的登录方法与 Client Registration；普通配置不能注入任意 OAuth
Endpoint、Scope、Header 或实现类。

产品通过 `LocalModelAuthenticationService` 执行列出连接、保存 API Key、启动/查询/取消外部登录和退出登录；
该 Service 是唯一公开写入口，并向模型 Adapter 暴露同一 `CredentialResolver`。接入未来第二个外部登录时，
新增 `ExternalLoginMethod` 并在产品 Composition Root 注册，不修改 Store、Coordinator 或产品 UI 状态机。

`auth.json` 是当前用户专属的明文本机存储，不提供静态加密。权限或 ACL、文件锁、严格 Schema、原子替换
任一检查失败时均拒绝使用。Secret 不得进入日志、异常、公共 View、Runtime SQLite、JSONL 或 Workspace。

验证：

```bash
./mvnw -pl :haifa-agent-local-model-auth -am test
```
