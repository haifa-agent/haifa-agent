# Spring Boot

Haifa Agent 把 Spring 保持在 Adapter 边界。Core、Runtime、SDK 与主要 Capability API 都可以在不依赖 Spring 的情况下使用。

Spring Boot Starter 只在以下条件同时成立时创建由容器托管的 HaifaAgent：

- classpath 中存在 Haifa Agent SDK / Starter；
- haifa.agent.enabled 为 true（默认）；
- 应用没有自行声明 HaifaAgent Bean。

## 添加 Starter

BOM 与 Dependency 坐标见 [安装](installation.md)。

## 配置默认 Credential

~~~bash
export DEEPSEEK_API_KEY="<your-api-key>"
~~~

## 注入 Agent

~~~java
package example;

import io.haifa.agent.sdk.api.HaifaAgent;
import org.springframework.stereotype.Service;

@Service
public final class HelloAgentService {
    private final HaifaAgent agent;

    public HelloAgentService(HaifaAgent agent) {
        this.agent = agent;
    }

    public String hello() throws InterruptedException {
        return agent.chat("Say hello in one sentence.").await().text();
    }
}
~~~

Spring Context 关闭时会自动关闭 Agent。

## Java Tool Bean

实现 JavaTool<I, O> 的 Bean 会按照 Spring 排序规则被收集并注册到 Agent：

~~~java
@Component
public final class WeatherTool
        implements JavaTool<WeatherTool.Request, WeatherTool.Response> {

    public record Request(String city) {}
    public record Response(String forecast) {}

    private static final JavaToolSpec<Request, Response> SPEC =
            JavaToolSpec.builder("weather_get", Request.class, Response.class)
                    .description("Get the current weather for a city")
                    .pure()
                    .build();

    @Override
    public JavaToolSpec<Request, Response> spec() {
        return SPEC;
    }

    @Override
    public Response invoke(Request input, JavaToolContext context) {
        return new Response("sunny");
    }
}
~~~

## 何时接管默认装配

默认 Auto-configuration 适合本地开发和简单应用。生产装配可以：

- 提供自己的 HaifaAgent Bean；
- 提供可信 SdkCallerProvider；
- 注册有序的 HaifaAgentStarterCustomizer Bean；
- 通过 haifa.agent.enabled=false 关闭默认装配。

默认 Starter 仍然只使用进程内状态，不应被视为 Durable Production Deployment。
