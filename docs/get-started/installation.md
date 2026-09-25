# 安装

Haifa Agent 当前面向 Java 21。feat-0.1.1-baseline 这条开发线的仓库版本为 0.1.1。

Snapshot 坐标并不代表对应制品已经发布到公共 Maven Repository。直接从源码使用时，请优先使用仓库自带的 Maven Wrapper。

## 前置条件

- JDK 21
- Git
- Windows、Linux 或 macOS 等支持的桌面/服务器操作系统
- 构建仓库无需额外安装 Maven

验证 Java：

~~~bash
java -version
~~~

## 从源码构建

~~~bash
git clone https://github.com/haifa-agent/haifa-agent.git
cd haifa-agent
./mvnw -DskipTests install
~~~

Windows PowerShell：

~~~powershell
git clone https://github.com/haifa-agent/haifa-agent.git
Set-Location haifa-agent
.\mvnw.cmd -DskipTests install
~~~

日常开发时，建议只构建实际需要的模块，而不是每次都安装完整 Reactor。

## Pure Java SDK

公开 SDK 的入门入口是 haifa-agent-sdk-starter。使用本地安装的 Snapshot 制品时，可以这样声明：

~~~xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.haifa</groupId>
            <artifactId>haifa-agent-bom</artifactId>
            <version>0.1.1</version>
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
~~~

## Spring Boot

Spring 应用应导入 Spring BOM，并使用 Spring Boot Starter，而不是再单独组装 Pure Java Starter：

~~~xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.haifa</groupId>
            <artifactId>haifa-agent-spring-bom</artifactId>
            <version>0.1.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.haifa</groupId>
        <artifactId>haifa-agent-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
~~~

接下来可阅读 [快速开始](quickstart.md) 或 [Spring Boot](spring-boot.md)。
