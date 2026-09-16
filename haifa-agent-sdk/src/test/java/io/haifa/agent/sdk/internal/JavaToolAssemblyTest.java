package io.haifa.agent.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.ToolPlatformContribution;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class JavaToolAssemblyTest {

    @Test
    void mergesJavaToolIntoExistingCatalogAndPreservesDispatchSequence() {
        JavaToolAssembly.Prepared prepared = JavaToolAssembly.prepare(existingPlatform(), List.of(new WeatherTool()));
        ToolPlatformContribution merged = prepared.platform();

        assertThat(merged.catalog().snapshot().bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("existing", "weather_get");
        assertThat(prepared.javaToolAliases()).containsExactly("weather_get");
        merged.catalog().snapshot().bindings().forEach(merged.invoker()::validateBinding);

        var weather = merged.catalog().findByAlias(new ToolAlias("weather_get")).orElseThrow();
        AtomicBoolean dispatched = new AtomicBoolean();
        AtomicBoolean acknowledged = new AtomicBoolean();
        ToolResult result = merged.invoker()
                .invoke(new ToolInvocationRequest(
                        weather,
                        new ToolCallId("call-1"),
                        new AgentRunId("run-1"),
                        new TenantRef("tenant-1"),
                        new PrincipalRef("user-1", "user"),
                        new ToolArguments(
                                weather.definition().inputSchema().id(),
                                weather.definition().inputSchema().version(),
                                Map.of("city", "Shanghai")),
                        Instant.parse("2026-08-05T01:02:03Z"),
                        java.util.Optional.empty(),
                        () -> false,
                        Map.of(),
                        new ToolInvocationObserver() {
                            @Override
                            public void dispatched() {
                                dispatched.set(true);
                            }

                            @Override
                            public void acknowledged() {
                                acknowledged.set(true);
                            }
                        }));
        assertThat(result.structuredData()).containsEntry("forecast", "Sunny in Shanghai");
        assertThat(dispatched).isTrue();
        assertThat(acknowledged).isTrue();

        assertThatThrownBy(() -> merged.invoker()
                        .invoke(new ToolInvocationRequest(
                                weather,
                                new ToolCallId("call-2"),
                                new AgentRunId("run-1"),
                                new TenantRef("tenant-1"),
                                new PrincipalRef("user-1", "user"),
                                new ToolArguments(
                                        weather.definition().inputSchema().id(),
                                        weather.definition().inputSchema().version(),
                                        Map.of("city", "fail")),
                                Instant.parse("2026-08-05T01:02:03Z"),
                                java.util.Optional.empty(),
                                () -> false,
                                Map.of())))
                .isInstanceOf(ToolInvocationException.class)
                .satisfies(failure -> {
                    ToolInvocationException invocation = (ToolInvocationException) failure;
                    assertThat(invocation.dispatchState()).isEqualTo(ToolDispatchState.DISPATCHED);
                    assertThat(invocation.getMessage()).isEqualTo("Java Tool invocation failed");
                });
    }

    @Test
    void producesTheSameCatalogDigestRegardlessOfJavaToolRegistrationOrder() {
        JavaToolAssembly.Prepared first = JavaToolAssembly.prepare(null, List.of(new WeatherTool(), new GeocodeTool()));
        JavaToolAssembly.Prepared second =
                JavaToolAssembly.prepare(null, List.of(new GeocodeTool(), new WeatherTool()));

        assertThat(first.platform().catalog().snapshot().digest())
                .isEqualTo(second.platform().catalog().snapshot().digest());
        assertThat(first.platform().catalog().snapshot().bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("geocode", "weather_get");
        assertThat(first.javaToolAliases()).containsExactlyInAnyOrder("weather_get", "geocode");
    }

    @Test
    void noJavaToolsReturnsBasePlatformUnchanged() {
        ToolPlatformContribution base = existingPlatform();

        JavaToolAssembly.Prepared prepared = JavaToolAssembly.prepare(base, List.of());

        assertThat(prepared.platform()).isSameAs(base);
        assertThat(prepared.javaToolAliases()).isEmpty();
        assertThat(JavaToolAssembly.prepare(null, List.of()).platform()).isNull();
    }

    @Test
    void rejectsDuplicateJavaToolAliasesAndBaseCatalogConflicts() {
        assertThatThrownBy(() -> JavaToolAssembly.prepare(null, List.of(new WeatherTool(), new DuplicateWeatherTool())))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("JAVA_TOOL_ALIAS_CONFLICT");
        assertThatThrownBy(() -> JavaToolAssembly.prepare(existingPlatform(), List.of(new ExistingAliasTool())))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("JAVA_TOOL_ALIAS_CONFLICT");
    }

    private static ToolPlatformContribution existingPlatform() {
        ToolProviderId providerId = new ToolProviderId("existing.provider");
        ToolSchema schema = new ToolSchema(
                "existing.schema",
                "1.0.0",
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", false));
        ToolDefinition definition = new ToolDefinition(
                new ToolName("existing"),
                new io.haifa.agent.tool.api.SemanticVersion("1.0.0"),
                providerId,
                "Existing",
                "Existing Tool",
                schema,
                schema,
                ToolExecutionMode.IN_PROCESS,
                true,
                Duration.ofSeconds(1),
                "per-run",
                ToolIdempotency.PURE,
                ToolRisk.LOW,
                Set.of(),
                ToolResourceRequirements.none(),
                List.of(),
                ToolApprovalRequirement.NEVER,
                "test",
                false,
                Set.of());
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return providerId;
            }

            @Override
            public ToolResult invoke(io.haifa.agent.tool.api.ToolInvocationRequest request) {
                return new ToolResult(true, "done", Map.of(), List.of(), List.of(), false);
            }
        };
        var catalog = new ToolCatalogBuilder()
                .register(new ToolAlias("existing"), definition, "existing", provider)
                .freeze();
        return new ToolPlatformContribution(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator());
    }

    public record WeatherRequest(String city) {}

    public record WeatherResponse(String forecast) {}

    private static final class WeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("weather_get", WeatherRequest.class, WeatherResponse.class)
                    .description("Gets the weather for a city")
                    .pure()
                    .build();
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            if (input.city().equals("fail")) throw new ToolInvocationException("sensitive provider detail");
            return new WeatherResponse("Sunny in " + input.city());
        }
    }

    private static final class GeocodeTool implements JavaTool<WeatherRequest, WeatherResponse> {
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("geocode", WeatherRequest.class, WeatherResponse.class)
                    .pure()
                    .build();
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse(input.city());
        }
    }

    private static final class DuplicateWeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("weather_get", WeatherRequest.class, WeatherResponse.class)
                    .pure()
                    .build();
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse(input.city());
        }
    }

    private static final class ExistingAliasTool implements JavaTool<WeatherRequest, WeatherResponse> {
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("existing", WeatherRequest.class, WeatherResponse.class)
                    .pure()
                    .build();
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse(input.city());
        }
    }
}
