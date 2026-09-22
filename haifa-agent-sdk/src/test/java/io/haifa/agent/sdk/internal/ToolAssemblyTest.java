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
import io.haifa.agent.sdk.contribution.ToolRegistration;
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

public class ToolAssemblyTest {

    @Test
    void registersJavaToolOnceAndPreservesDispatchSequence() {
        ToolAssembly.Prepared prepared = ToolAssembly.prepare(null, List.of(new WeatherTool()), List.of());
        ToolPlatformContribution platform = prepared.platform();

        assertThat(platform.catalog().snapshot().bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("weather_get");
        assertThat(prepared.contributedAliases()).containsExactly("weather_get");
        assertThat(platform.catalog().snapshot().digest())
                .isEqualTo(platform.catalog()
                        .findByAlias(new ToolAlias("weather_get"))
                        .orElseThrow()
                        .catalogDigest());
        platform.catalog().snapshot().bindings().forEach(platform.invoker()::validateBinding);

        var weather =
                platform.catalog().findByAlias(new ToolAlias("weather_get")).orElseThrow();
        AtomicBoolean dispatched = new AtomicBoolean();
        AtomicBoolean acknowledged = new AtomicBoolean();
        ToolResult result = platform.invoker()
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

        assertThatThrownBy(() -> platform.invoker()
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
        ToolAssembly.Prepared first =
                ToolAssembly.prepare(null, List.of(new WeatherTool(), new GeocodeTool()), List.of());
        ToolAssembly.Prepared second =
                ToolAssembly.prepare(null, List.of(new GeocodeTool(), new WeatherTool()), List.of());

        assertThat(first.platform().catalog().snapshot().digest())
                .isEqualTo(second.platform().catalog().snapshot().digest());
        assertThat(first.platform().catalog().snapshot().bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("geocode", "weather_get");
        assertThat(first.contributedAliases()).containsExactlyInAnyOrder("weather_get", "geocode");
    }

    @Test
    void noJavaToolsReturnsBasePlatformUnchanged() {
        ToolPlatformContribution base = existingPlatform();

        ToolAssembly.Prepared prepared = ToolAssembly.prepare(base, List.of(), List.of());

        assertThat(prepared.platform()).isSameAs(base);
        assertThat(prepared.contributedAliases()).isEmpty();
        assertThat(ToolAssembly.prepare(null, List.of(), List.of()).platform()).isNull();
    }

    @Test
    void rejectsDuplicateJavaToolAliases() {
        assertThatThrownBy(() ->
                        ToolAssembly.prepare(null, List.of(new WeatherTool(), new DuplicateWeatherTool()), List.of()))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("JAVA_TOOL_ALIAS_CONFLICT");
    }

    @Test
    void registersJavaAndIntegrationToolsInOneCatalogFreeze() {
        ToolAssembly.Prepared prepared =
                ToolAssembly.prepare(null, List.of(new WeatherTool()), List.of(integrationTool("remote_search")));

        assertThat(prepared.platform().catalog().snapshot().bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("remote_search", "weather_get");
        assertThat(prepared.contributedAliases()).containsExactlyInAnyOrder("weather_get", "remote_search");
        assertThat(prepared.platform().catalog().snapshot().bindings())
                .extracting(binding -> binding.catalogDigest())
                .containsOnly(prepared.platform().catalog().snapshot().digest());
        prepared.platform()
                .catalog()
                .snapshot()
                .bindings()
                .forEach(prepared.platform().invoker()::validateBinding);
    }

    @Test
    void rejectsAnIntegrationToolThatCollidesWithAJavaTool() {
        assertThatThrownBy(() ->
                        ToolAssembly.prepare(null, List.of(new WeatherTool()), List.of(integrationTool("weather_get"))))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("TOOL_ALIAS_CONFLICT");
    }

    @Test
    void rejectsTwoIntegrationToolsThatShareOneAlias() {
        assertThatThrownBy(() -> ToolAssembly.prepare(
                        null, List.of(), List.of(integrationTool("remote_search"), integrationTool("remote_search"))))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("TOOL_ALIAS_CONFLICT");
    }

    @Test
    void registersIntegrationToolsWithoutAnyJavaTool() {
        ToolAssembly.Prepared prepared = ToolAssembly.prepare(null, List.of(), List.of(integrationTool("remote_only")));

        assertThat(prepared.platform().catalog().snapshot().bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("remote_only");
    }

    private static ToolRegistration integrationTool(String alias) {
        ToolProviderId providerId = new ToolProviderId("mcp." + alias);
        ToolSchema schema = new ToolSchema(
                "mcp." + alias,
                "1.0.0",
                Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true));
        ToolDefinition definition = new ToolDefinition(
                new ToolName(alias),
                new io.haifa.agent.tool.api.SemanticVersion("1.0.0"),
                providerId,
                alias,
                "Imported " + alias,
                schema,
                schema,
                ToolExecutionMode.REMOTE_PROVIDER,
                true,
                Duration.ofSeconds(30),
                "mcp-server:test",
                ToolIdempotency.IDEMPOTENT,
                ToolRisk.LOW,
                Set.of(io.haifa.agent.tool.api.ToolSideEffect.NETWORK_ACCESS),
                new ToolResourceRequirements(Set.of(), Set.of("partner.example.com"), Set.of()),
                List.of(),
                ToolApprovalRequirement.POLICY,
                "mcp:test",
                false,
                Set.of("mcp", "remote"));
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return providerId;
            }

            @Override
            public ToolResult invoke(ToolInvocationRequest request) {
                return new ToolResult(true, "remote ok", Map.of(), List.of(), List.of(), false);
            }
        };
        return new ToolRegistration(new ToolAlias(alias), definition, "mcp:test:" + alias, provider);
    }

    @Test
    void rejectsCombiningJavaToolsWithAnExistingToolPlatform() {
        assertThatThrownBy(() -> ToolAssembly.prepare(existingPlatform(), List.of(new WeatherTool()), List.of()))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("JAVA_TOOL_PLATFORM_UNSUPPORTED");
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
}
