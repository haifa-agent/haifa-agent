package io.haifa.example.consumer.plain;

import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;

/** Application-owned typed Tool registered by the standalone pure Java consumer. */
public final class WeatherTool implements JavaTool<WeatherTool.Request, WeatherTool.Response> {
    public record Request(String city) {}

    public record Response(String forecast) {}

    private static final JavaToolSpec<Request, Response> SPEC = JavaToolSpec.builder(
                    "weather.get", Request.class, Response.class)
            .alias("weather_get")
            .title("Weather")
            .description("Get deterministic example weather for a city")
            .pure()
            .build();

    @Override
    public JavaToolSpec<Request, Response> spec() {
        return SPEC;
    }

    @Override
    public Response invoke(Request input, JavaToolContext context) {
        return new Response("Cloudy in " + input.city() + ", 28°C");
    }

    @Override
    public String summarize(Response output) {
        return output.forecast();
    }
}
