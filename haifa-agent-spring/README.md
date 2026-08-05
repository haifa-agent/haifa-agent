# Haifa Agent Spring

Spring 生态适配聚合层。它把 Spring Boot 的配置与 Bean 生命周期映射到既有纯 Java SDK，不在
Spring 层复制 Product、Runtime、Catalog、Policy 或 Provider 语义。

包含：

- `haifa-agent-spring-boot-autoconfigure`：条件装配、配置绑定、Java Tool Bean 收集和安全失败分析；
- `haifa-agent-spring-boot-starter`：外部 Spring Boot 应用使用的依赖入口。

Spring AI 与 Spring AI Alibaba 不进入这两个模块。默认模型和运行边界由纯 Java
`haifa-agent-sdk-starter` 提供：DeepSeek V4 Flash、Thinking disabled、进程内开发 Store。

验证：

```bash
./mvnw -pl :haifa-agent-spring-boot-starter -am test
./mvnw -pl :haifa-agent-spring-boot-starter -am -Prelease verify
```
