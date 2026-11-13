# 安装

## Coding Agent

一键安装支持 Windows x64 和 Ubuntu 24.04 / WSL2 Ubuntu 24.04 x64。发行包内置 Java 21，
安装到当前用户目录。安装器从 GitHub Releases 下载并校验发行包，安装完成后可在同一个终端运行 `haifa`。

安装命令在首个包含 `install-haifa.ps1` 和 `install-haifa.sh` 的正式版发布后可用。
也可以先从 [GitHub Releases](https://github.com/haifa-agent/haifa-agent/releases) 下载并查看安装脚本。

**Windows x64（PowerShell）**

```powershell
& ([scriptblock]::Create([Text.Encoding]::UTF8.GetString((Invoke-WebRequest -UseBasicParsing 'https://github.com/haifa-agent/haifa-agent/releases/latest/download/install-haifa.ps1').RawContentStream.ToArray())))
```

**Linux x64（Bash，需要 `curl`）**

```bash
installer=$(mktemp) && (trap 'rm -f "$installer"' EXIT; curl -fLSs https://github.com/haifa-agent/haifa-agent/releases/latest/download/install-haifa.sh -o "$installer" && sh "$installer") && export PATH="$HOME/.local/bin:$PATH"
```

进入项目目录运行 `haifa`。Linux / WSL2 用户可显式启用文件认证存储，用 `HAIFA_CODING_AUTH_STORE=file haifa`
启动并按终端引导连接模型。之后启动需使用同一存储设置。模型凭据保存在 `~/.haifa-agent/auth.json`，权限为 `0600`。

重新执行安装命令可升级。安装器会校验新版压缩包，然后切换 `haifa` 指向的版本。
Windows 安装目录为 `%LOCALAPPDATA%\HaifaAgent`，Linux 安装目录为
`~/.local/share/haifa-agent`，命令位于 `~/.local/bin/haifa`。两端的会话和日志默认存放在
用户目录下的 `.haifa-agent/coding`，独立于安装目录。升级会保留旧版本的程序文件。要切换到指定版本，将链接中的
`releases/latest/download/` 换成 `releases/download/<tag>/`，选择带安装脚本的 Release。

## Java SDK

Haifa Agent 面向 Java 21。当前源码开发线为 `0.1.6-SNAPSHOT`，下文以正式版 `0.1.5` SDK 为例。

SDK 通过 [GitHub Releases](https://github.com/haifa-agent/haifa-agent/releases) 的
`haifa-agent-sdk-v<version>.zip` 分发。下载可用版本，或从对应源码构建发行包，再使用与 ZIP 相同的 BOM 版本。
当前不承诺 Maven Central 上存在这些坐标。
解压后配置 `repository/` 文件仓库，再按下文导入 BOM。第三方依赖仍从 Maven Central 下载。

~~~xml
<repositories>
    <repository>
        <id>haifa-release</id>
        <url>file:///D:/packages/haifa-agent-sdk-v0.1.5/repository</url>
    </repository>
</repositories>
~~~

将 URL 替换为解压目录的绝对文件 URL；macOS/Linux 使用 `file:///absolute/path/.../repository`。
Windows Personal Assistant 便携包内置 Java 21，可从同一 Release 下载。

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
            <version>0.1.5</version>
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
            <version>0.1.5</version>
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
