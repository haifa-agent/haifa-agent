# Java Tools

The SDK provides a typed Java Tool path for application code that does not need to work directly with the lower-level Tool Catalog and provider APIs.

## Define a Tool

Use Java records for the bounded input/output contract:

~~~java
public record WeatherRequest(String city) {}
public record WeatherResponse(String forecast) {}

public final class WeatherTool
        implements JavaTool<WeatherRequest, WeatherResponse> {

    private static final JavaToolSpec<WeatherRequest, WeatherResponse> SPEC =
            JavaToolSpec.builder(
                            "weather_get",
                            WeatherRequest.class,
                            WeatherResponse.class)
                    .description("Get the current weather for a city")
                    .pure()
                    .build();

    @Override
    public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
        return SPEC;
    }

    @Override
    public WeatherResponse invoke(
            WeatherRequest input,
            JavaToolContext context) {
        return new WeatherResponse("sunny");
    }
}
~~~

Register it with the Starter:

~~~java
try (var agent = HaifaAgentStarter.builder()
        .tool(new WeatherTool())
        .build()) {
    // use the Agent
}
~~~

Spring Boot applications can register the same implementation as a Spring bean.

## Schema and execution

The SDK derives the bounded JSON Schema/codec from the record type and registers the Tool into the shared Tool Core pipeline.

The application does not need to manually build Catalog digests, frozen bindings, Invokers, or schema validators for this path.

## Risk declaration

pure() is the explicit low-risk declaration for a Tool that has no external side effect.

A Tool that performs network mutation, filesystem writes, process execution, purchases, account changes, or other external side effects must not be marked pure.

Policy and Approval remain separate from the Tool implementation.

## Runtime path

A model Tool Call is not executed directly. It passes through:

1. Tool identity/binding resolution;
2. input schema validation;
3. Policy and, when required, exact-target Approval;
4. credential/execution boundaries;
5. provider invocation;
6. result validation and persistence.

Unknown side-effect outcomes are not blindly retried.

## When to use the lower-level Tool API

Use the complete Tool API rather than JavaTool when you need provider-owned identity, advanced resource declarations, custom credential semantics, or a pre-built Tool platform.
