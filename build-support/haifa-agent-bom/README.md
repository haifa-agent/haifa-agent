# Haifa Agent BOM

统一管理 Haifa Agent 内部模块及纯 Java 基础依赖版本。

- 允许：内部模块、Jackson、SLF4J、Reactor 和测试类库的版本约束。
- 禁止：Spring Boot、Spring AI、Spring AI Alibaba BOM。
- 本模块只提供 `dependencyManagement`，不包含运行时代码。
