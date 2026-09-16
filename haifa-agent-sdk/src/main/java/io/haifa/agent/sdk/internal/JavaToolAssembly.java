package io.haifa.agent.sdk.internal;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.ToolPlatformContribution;
import io.haifa.agent.sdk.tool.JavaRecordSchemaGenerator;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.core.DefaultToolCatalog;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Internal adapter that turns per-Tool Java registrations into the unified Tool platform.
 *
 * <p>Registration is one pass and one freeze: each {@link JavaTool} is converted into a Tool Core
 * {@link ToolDefinition} plus {@link ToolProvider}, registered on a {@link ToolCatalogBuilder} and
 * frozen once. The SDK does not merge catalogs, does not re-digest frozen bindings and does not
 * multiplex schema validators; catalog composition is the Tool platform's own concern.
 */
public final class JavaToolAssembly {
    private static final String CONCURRENCY_POLICY = "per-run";
    private static final String PROVENANCE = "java-sdk";

    private JavaToolAssembly() {}

    /**
     * Registers Java Tools as the Tool platform and returns the aliases those Tools added.
     *
     * <p>When no Java Tool is registered the supplied platform is returned unchanged; when the host
     * supplied no platform a Java-only platform is created. A host that already owns a Tool platform
     * registers its Tools through that platform, so combining both is rejected instead of silently
     * rewriting the platform's catalog and bindings.
     */
    public static Prepared prepare(ToolPlatformContribution base, List<? extends JavaTool<?, ?>> javaTools) {
        List<JavaTool<?, ?>> tools = List.copyOf(Objects.requireNonNull(javaTools, "javaTools must not be null"));
        if (tools.isEmpty()) return new Prepared(base, Set.of());
        if (base != null) {
            throw new HaifaAgentException(
                    "JAVA_TOOL_PLATFORM_UNSUPPORTED",
                    "product.assemble",
                    "assembly",
                    "Java Tools cannot be combined with an existing Tool platform; register them on that platform");
        }

        ToolCatalogBuilder builder = new ToolCatalogBuilder();
        Set<String> aliases = new LinkedHashSet<>();
        JavaRecordSchemaGenerator schemas = new JavaRecordSchemaGenerator();
        for (JavaTool<?, ?> tool : tools) {
            Objects.requireNonNull(tool, "Java Tool must not be null");
            JavaToolSpec<?, ?> spec = Objects.requireNonNull(tool.spec(), "Java Tool spec must not be null");
            if (!aliases.add(spec.alias().value())) {
                throw new HaifaAgentException(
                        "JAVA_TOOL_ALIAS_CONFLICT",
                        "product.assemble",
                        "assembly",
                        "duplicate Java Tool alias was supplied");
            }
            String schemaPrefix = "urn:haifa:java-tool:" + spec.name().value();
            ToolSchema input =
                    schemas.generate(schemaPrefix + ":input", spec.version().value(), spec.inputType());
            ToolSchema output =
                    schemas.generate(schemaPrefix + ":output", spec.version().value(), spec.outputType());
            ToolProviderId providerId = providerId(spec);
            ToolDefinition definition = new ToolDefinition(
                    spec.name(),
                    spec.version(),
                    providerId,
                    spec.title(),
                    spec.description(),
                    input,
                    output,
                    ToolExecutionMode.IN_PROCESS,
                    true,
                    spec.timeout(),
                    CONCURRENCY_POLICY,
                    spec.idempotency(),
                    spec.risk(),
                    spec.sideEffects(),
                    ToolResourceRequirements.none(),
                    List.of(),
                    spec.approvalRequirement(),
                    PROVENANCE,
                    false,
                    Set.of());
            builder.register(
                    spec.alias(),
                    definition,
                    "java-tool:" + spec.name().value() + "@" + spec.version().value(),
                    provider(tool, spec, providerId));
        }
        DefaultToolCatalog catalog = builder.freeze();
        return new Prepared(
                new ToolPlatformContribution(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator()),
                Set.copyOf(aliases));
    }

    private static ToolProviderId providerId(JavaToolSpec<?, ?> spec) {
        return new ToolProviderId("java." + spec.name().value());
    }

    private static <I extends Record, O extends Record> ToolProvider typedProvider(
            JavaTool<I, O> tool, JavaToolSpec<I, O> spec, ToolProviderId providerId) {
        return new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return providerId;
            }

            @Override
            public ToolResult invoke(ToolInvocationRequest request) {
                ToolDispatchState dispatchState = ToolDispatchState.NOT_DISPATCHED;
                try {
                    I input = JavaRecordSupport.decode(
                            spec.inputType(), request.arguments().values());
                    request.cancellation().throwIfCancellationRequested();
                    JavaToolContext context = new JavaToolContext(
                            request.runId(),
                            request.tenant(),
                            request.principal(),
                            request.deadline(),
                            request.idempotencyKey(),
                            request.cancellation(),
                            request.credentials());
                    request.observer().dispatched();
                    dispatchState = ToolDispatchState.DISPATCHED;
                    O output =
                            Objects.requireNonNull(tool.invoke(input, context), "Java Tool returned a null response");
                    request.observer().acknowledged();
                    dispatchState = ToolDispatchState.ACKNOWLEDGED;
                    return new ToolResult(
                            true,
                            Objects.requireNonNull(tool.summarize(output), "Java Tool summary must not be null"),
                            JavaRecordSupport.encode(output),
                            List.of(),
                            List.of(),
                            false);
                } catch (ToolInvocationException exception) {
                    ToolDispatchState reported = exception.dispatchState() == ToolDispatchState.NOT_DISPATCHED
                                    && dispatchState != ToolDispatchState.NOT_DISPATCHED
                            ? dispatchState
                            : exception.dispatchState();
                    throw new ToolInvocationException(
                            exception.failureCode(), reported, "Java Tool invocation failed", exception);
                } catch (RuntimeException exception) {
                    throw new ToolInvocationException(
                            "JAVA_TOOL_INVOCATION_FAILED", dispatchState, "Java Tool invocation failed", exception);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ToolProvider provider(JavaTool<?, ?> tool, JavaToolSpec<?, ?> spec, ToolProviderId providerId) {
        return typedProvider((JavaTool<Record, Record>) tool, (JavaToolSpec<Record, Record>) spec, providerId);
    }

    /** Effective Tool platform after Java Tool registration and the aliases those Tools added. */
    public record Prepared(ToolPlatformContribution platform, Set<String> javaToolAliases) {
        public Prepared {
            javaToolAliases = Set.copyOf(Objects.requireNonNull(javaToolAliases, "javaToolAliases must not be null"));
        }
    }
}
