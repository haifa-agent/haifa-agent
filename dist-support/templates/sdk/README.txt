========================================================================
Haifa Agent SDK Release Bundle
========================================================================

本发行包包含 Haifa Agent 纯 Java SDK、SDK Starter 及 Spring Boot 自动装配模块的所有核心 JAR、源码与 Javadoc。

【包含组件】
- haifa-agent-sdk: 核心 Agent SDK API
- haifa-agent-sdk-starter: 纯 Java 零配置极简入门 Starter (内置 DeepSeek V4 Flash 默认装配)
- haifa-agent-spring-boot-starter: Spring Boot 3 自动装配 Starter
- haifa-agent-spring-boot-autoconfigure: Spring Boot 配置元数据
- haifa-agent-bom: 纯 Java BOM (Dependency Management)
- haifa-agent-spring-bom: Spring 生态 BOM

【Maven 依赖配置 (纯 Java)】
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.haifa</groupId>
            <artifactId>haifa-agent-bom</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.haifa</groupId>
        <artifactId>haifa-agent-sdk-starter</artifactId>
    </dependency>
</dependencies>

【Gradle 依赖配置】
dependencies {
    implementation platform("io.haifa:haifa-agent-bom:0.1.0")
    implementation "io.haifa:haifa-agent-sdk-starter"
}

【纯 Java 最小代码示例】
try (HaifaAgent agent = HaifaAgentStarter.create()) {
    AgentRunResult result = agent.chat("Hello Haifa Agent!");
    System.out.println(result.output());
}

========================================================================
项目主页: https://github.com/haifa-agent/haifa-agent
========================================================================
