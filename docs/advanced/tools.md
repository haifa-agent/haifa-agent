# Java Tools

SDK 提供了一条类型化 Java Tool 路径，使应用代码不需要直接操作更底层的 Tool Catalog 与 Provider API。

## 定义 Tool

使用 Java record 声明有界输入/输出契约：

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

通过 Starter 注册：

~~~java
try (var agent = HaifaAgentStarter.builder()
        .tool(new WeatherTool())
        .build()) {
    // use the Agent
}
~~~

Spring Boot 应用可以把同一个实现注册成 Spring Bean。

## Schema 与执行

SDK 会从 record 类型派生有界 JSON Schema / Codec，并把 Tool 注册进共享 Tool Core Pipeline。

使用这条路径时，应用不需要手工构造 Catalog digest、Frozen Binding、Invoker 或 Schema Validator。

## Risk 声明

pure() 是一个明确的低风险声明，只适用于真正没有外部 Side Effect 的 Tool。

会进行网络写入、文件修改、进程执行、购买、账户变更或其它外部 Side Effect 的 Tool，不能标记为 pure。

Policy 与 Approval 仍然独立于 Tool 实现本身。

## Runtime 执行路径

Model 产生的 Tool Call 不会直接执行，而会经过：

1. Tool identity / binding resolution；
2. Input Schema Validation；
3. Policy，以及必要时的 exact-target Approval；
4. Credential / Execution 边界；
5. Provider invocation；
6. Result Validation 与 Persistence。

有 Side Effect 的 Tool 如果 Outcome Unknown，不会被盲目 Retry。

## 何时直接使用底层 Tool API

如果你需要 Provider-owned identity、高级 Resource 声明、自定义 Credential 语义或已经存在完整 Tool Platform，则应直接使用完整 Tool API，而不是 JavaTool Convenience API。
