# Haifa Agent Spring Boot Starter

外部 Spring Boot 应用的单依赖入口：

```xml
<dependency>
    <groupId>io.haifa</groupId>
    <artifactId>haifa-agent-spring-boot-starter</artifactId>
</dependency>
```

设置 `DEEPSEEK_API_KEY` 后，应用可以直接注入 `HaifaAgent`。任意实现
`JavaTool<I, O>` 的 Spring Bean 都会自动加入该 Agent。

Starter 只适合首次体验和进程内开发：Runtime 与 Conversation 状态不会在进程重启后恢复。生产系统
应提供自己的 `HaifaAgent` Bean，显式装配持久化、可信身份、Policy、Credential 与所需 Capability。
