# Installation

Haifa Agent currently targets Java 21. The repository version on the feat-0.1.1-baseline line is 0.1.1-SNAPSHOT.

The snapshot is not a promise that artifacts have been published to a public Maven repository. When working from source, use the Maven Wrapper shipped with the repository.

## Prerequisites

- JDK 21
- Git
- a supported desktop/server operating system
- no separately installed Maven is required for repository builds

Verify Java:

~~~bash
java -version
~~~

## Build from source

~~~bash
git clone https://github.com/haifa-agent/haifa-agent.git
cd haifa-agent
./mvnw -DskipTests install
~~~

On Windows PowerShell:

~~~powershell
git clone https://github.com/haifa-agent/haifa-agent.git
Set-Location haifa-agent
.\mvnw.cmd -DskipTests install
~~~

For day-to-day work, prefer building only the modules you need instead of installing the full reactor.

## Pure Java SDK

The public SDK entry point is haifa-agent-sdk-starter. When consuming locally installed snapshot artifacts:

~~~xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.haifa</groupId>
            <artifactId>haifa-agent-bom</artifactId>
            <version>0.1.1-SNAPSHOT</version>
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

Spring applications should import the Spring BOM and use the Spring Boot Starter rather than assembling the pure Java Starter separately:

~~~xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.haifa</groupId>
            <artifactId>haifa-agent-spring-bom</artifactId>
            <version>0.1.1-SNAPSHOT</version>
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

Continue with the [Quickstart](quickstart.md) or [Spring Boot](spring-boot.md).
