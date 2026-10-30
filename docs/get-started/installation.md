# 安装

Haifa Agent 0.1.4 面向 Java 21。

正式 SDK 通过 [GitHub Release](https://github.com/haifa-agent/haifa-agent/releases/tag/v0.1.4)
的 `haifa-agent-sdk-v0.1.4.zip` 分发；当前不承诺 Maven Central 上存在这些坐标。
解压后配置 `repository/` 文件仓库，再按下文导入 BOM。第三方依赖仍从 Maven Central 下载。

~~~xml
<repositories>
    <repository>
        <id>haifa-release</id>
        <url>file:///D:/packages/haifa-agent-sdk-v0.1.4/repository</url>
    </repository>
</repositories>
~~~

将 URL 替换为解压目录的绝对文件 URL；macOS/Linux 使用 `file:///absolute/path/.../repository`。
Windows CA / PA 便携包内置 Java 21，可从同一 Release 下载。

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
./mvnw -Pci-fast clean verify
~~~

Windows PowerShell：

~~~powershell
git clone https://github.com/haifa-agent/haifa-agent.git
Set-Location haifa-agent
.\build-support\scripts\invoke-haifa-maven.ps1 --layer L3 '--' -Pci-fast clean verify
~~~

日常开发时，建议只构建实际需要的模块。本地开发版 SDK 发布使用
`scripts/publish-local-sdk.ps1`；正式发行包构建见 [发行说明](../../dist-support/README.md)。

## Pure Java SDK

公开 SDK 的入门入口是 haifa-agent-sdk-starter。配置解压后的文件仓库后，可以这样声明：

~~~xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.haifa</groupId>
            <artifactId>haifa-agent-bom</artifactId>
            <version>0.1.4</version>
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
            <version>0.1.4</version>
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
