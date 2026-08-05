package io.haifa.agent.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.contribution.ToolPlatformContribution;
import io.haifa.agent.sdk.product.ProductAssemblyException;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityRequirement;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class JavaToolAssemblyTest {
    private static final ProductContributionCoordinate EXISTING_COORDINATE =
            new ProductContributionCoordinate("tool.existing", "1.0");

    @Test
    void mergesJavaToolIntoExistingCatalogAndUpdatesProfileInternally() {
        ToolPlatformContribution existing = existingPlatform();
        ProductProfile profile = profileWithExistingTool();

        JavaToolAssembly.Prepared prepared =
                JavaToolAssembly.prepare(profile, List.of(existing), List.of(new WeatherTool()));
        ToolPlatformContribution merged = prepared.contributions().stream()
                .filter(ToolPlatformContribution.class::isInstance)
                .map(ToolPlatformContribution.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(merged.catalog().snapshot().bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("existing", "weather_get");
        assertThat(prepared.profile().allowedTools()).containsExactlyInAnyOrder("existing", "weather_get");
        assertThat(prepared.profile().requirement(ProductCapabilities.TOOL).allowedContributions())
                .containsExactly(JavaToolAssembly.COORDINATE);
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
                        java.util.Optional.empty(),
                        () -> false,
                        List.of(),
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
                                List.of())))
                .isInstanceOf(ToolInvocationException.class)
                .satisfies(failure -> {
                    ToolInvocationException invocation = (ToolInvocationException) failure;
                    assertThat(invocation.dispatchState()).isEqualTo(ToolDispatchState.DISPATCHED);
                    assertThat(invocation.getMessage()).isEqualTo("Java Tool invocation failed");
                });
    }

    @Test
    void producesTheSameCatalogDigestRegardlessOfJavaToolRegistrationOrder() {
        ProductProfile profile = SdkTestFixtures.profile("java-tool-order", Map.of());

        JavaToolAssembly.Prepared first =
                JavaToolAssembly.prepare(profile, List.of(), List.of(new WeatherTool(), new GeocodeTool()));
        JavaToolAssembly.Prepared second =
                JavaToolAssembly.prepare(profile, List.of(), List.of(new GeocodeTool(), new WeatherTool()));

        assertThat(toolPlatform(first).catalog().snapshot().digest())
                .isEqualTo(toolPlatform(second).catalog().snapshot().digest());
        assertThat(toolPlatform(first).catalog().snapshot().bindings())
                .extracting(binding -> binding.alias().value())
                .containsExactly("geocode", "weather_get");
    }

    @Test
    void rejectsAliasConflictsAndMultipleBasePlatforms() {
        ProductProfile profile = SdkTestFixtures.profile("java-tool-conflicts", Map.of());

        assertThatThrownBy(() -> JavaToolAssembly.prepare(
                        profile, List.of(), List.of(new WeatherTool(), new ConflictingAliasTool())))
                .isInstanceOf(ProductAssemblyException.class)
                .hasMessageContaining("duplicate Java Tool alias");
        assertThatThrownBy(() -> JavaToolAssembly.prepare(
                        profile, List.of(existingPlatform(), existingPlatform()), List.of(new WeatherTool())))
                .isInstanceOf(ProductAssemblyException.class)
                .hasMessageContaining("multiple Tool contributions");
    }

    private static ToolPlatformContribution toolPlatform(JavaToolAssembly.Prepared prepared) {
        return prepared.contributions().stream()
                .filter(ToolPlatformContribution.class::isInstance)
                .map(ToolPlatformContribution.class::cast)
                .findFirst()
                .orElseThrow();
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
        return new ToolPlatformContribution(
                SdkTestFixtures.metadata(
                        EXISTING_COORDINATE,
                        ProductCapabilities.TOOL,
                        "sha256:" + catalog.snapshot().digest(),
                        ProductProviderSuitability.PRODUCTION),
                catalog,
                new DefaultToolInvoker(catalog),
                new JsonSchema202012Validator());
    }

    private static ProductProfile profileWithExistingTool() {
        ProductProfile base = SdkTestFixtures.profile("java-tool-assembly", Map.of());
        var requirements = new LinkedHashMap<>(base.capabilityRequirements());
        requirements.put(
                ProductCapabilities.TOOL,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.TOOL, Set.of(EXISTING_COORDINATE), ProductProviderSuitability.PRODUCTION));
        return ProductProfile.create(
                base.productId(),
                base.productVersion(),
                base.definitionId(),
                base.definitionVersion(),
                base.runProfileId(),
                base.runProfileVersion(),
                base.instructions(),
                base.budget(),
                base.limits(),
                base.policies(),
                requirements,
                Set.of("existing"),
                base.allowedSkills(),
                base.allowedExtensions());
    }

    public record WeatherRequest(String city) {}

    public record WeatherResponse(String forecast) {}

    private static final class WeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("weather.get", WeatherRequest.class, WeatherResponse.class)
                    .alias("weather_get")
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

    private static final class ConflictingAliasTool implements JavaTool<WeatherRequest, WeatherResponse> {
        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return JavaToolSpec.builder("weather.other", WeatherRequest.class, WeatherResponse.class)
                    .alias("weather_get")
                    .pure()
                    .build();
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse(input.city());
        }
    }
}
