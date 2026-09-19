# Spring Boot

Haifa Agent keeps Spring at the adapter edge. Core, Runtime, SDK, and capability APIs remain usable without Spring.

The Spring Boot Starter creates a container-managed HaifaAgent only when:

- Haifa Agent SDK/Starter classes are present;
- haifa.agent.enabled is true (the default);
- the application has not already provided a HaifaAgent bean.

## Add the Starter

See [Installation](installation.md) for the BOM and dependency coordinates.

## Set the default credential

~~~bash
export DEEPSEEK_API_KEY="<your-api-key>"
~~~

## Inject the Agent

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

The container closes the Agent when the application context shuts down.

## Java Tool beans

Beans implementing JavaTool<I, O> are collected in Spring ordering and registered into the Agent:

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

## Taking control

Use the default auto-configuration for local development and simple applications. For a production assembly, you can:

- provide your own HaifaAgent bean;
- provide a trusted SdkCallerProvider;
- register ordered HaifaAgentStarterCustomizer beans;
- disable the default path with haifa.agent.enabled=false.

The default Starter remains process-local and is not a durable production deployment.
