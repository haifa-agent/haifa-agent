# Haifa Agent Spring BOM

在 `haifa-agent-bom` 之上统一管理 Spring Boot、Spring AI、Spring AI Alibaba，以及 Haifa Agent
Spring Boot Auto-configuration/Starter 的版本。

- 允许：导入 Haifa Agent BOM 与 Spring 生态 BOM。
- 禁止：被纯 Java BOM、Common、Core 或 Runtime API 反向依赖。
- 当前兼容线：Spring Boot 3.5.x、Spring AI 1.1.x、Spring AI Alibaba 1.1.x。
- 当前业务实现只使用 Spring Boot；Spring AI 与 Spring AI Alibaba 仍为预留版本线。
- Spring 依赖线使用 SLF4J 2.0.18，以匹配 Spring Boot 3.5.16 的日志桥接依赖；纯 Java BOM 仍保持
  自己的版本线。
