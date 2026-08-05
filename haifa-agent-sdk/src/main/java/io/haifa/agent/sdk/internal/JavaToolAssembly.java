package io.haifa.agent.sdk.internal;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.contribution.ToolPlatformContribution;
import io.haifa.agent.sdk.product.ProductAssemblyException;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityRequirement;
import io.haifa.agent.sdk.product.ProductContribution;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.sdk.tool.JavaRecordSchemaGenerator;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolCatalog;
import io.haifa.agent.tool.api.ToolCatalogSnapshot;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolInvoker;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSchemaValidator;
import io.haifa.agent.tool.core.DefaultToolCatalog;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Internal adapter that turns per-Tool Java registrations into the unified Tool platform. */
public final class JavaToolAssembly {
    static final ProductContributionCoordinate COORDINATE =
            new ProductContributionCoordinate("sdk.java-tools", "1.0.0");

    private JavaToolAssembly() {}

    public static Prepared prepare(
            ProductProfile profile,
            List<? extends ProductContribution> contributions,
            List<? extends JavaTool<?, ?>> javaTools) {
        Objects.requireNonNull(profile, "profile must not be null");
        List<ProductContribution> supplied =
                List.copyOf(Objects.requireNonNull(contributions, "contributions must not be null"));
        List<JavaTool<?, ?>> tools = List.copyOf(Objects.requireNonNull(javaTools, "javaTools must not be null"));
        if (tools.isEmpty()) return new Prepared(profile, supplied, Map.of());

        List<ProductContribution> toolContributions = supplied.stream()
                .filter(value -> ProductCapabilities.TOOL.equals(value.capabilityId()))
                .toList();
        if (toolContributions.size() > 1) {
            throw new ProductAssemblyException(
                    "JAVA_TOOL_PLATFORM_AMBIGUOUS", "Java Tools cannot be merged with multiple Tool contributions");
        }
        ToolPlatformContribution base = null;
        if (!toolContributions.isEmpty()) {
            ProductContribution candidate = toolContributions.getFirst();
            if (!(candidate instanceof ToolPlatformContribution platform)) {
                throw new ProductAssemblyException(
                        "JAVA_TOOL_PLATFORM_INVALID", "Java Tools require a ToolPlatformContribution to merge with");
            }
            base = platform;
            base.validate();
        }

        RegisteredTools registered = register(tools);
        MergedPlatform merged = merge(base, registered);
        ProductProfile effectiveProfile = extendProfile(profile, merged.contribution(), registered.aliases());
        List<ProductContribution> effectiveContributions = new ArrayList<>(supplied);
        if (base != null) effectiveContributions.remove(base);
        effectiveContributions.add(merged.contribution());
        Map<ProductContribution, ProductContribution> lifecycleReplacements =
                base == null ? Map.of() : Map.of(merged.contribution(), base);
        return new Prepared(effectiveProfile, effectiveContributions, lifecycleReplacements);
    }

    private static RegisteredTools register(List<JavaTool<?, ?>> tools) {
        ToolCatalogBuilder builder = new ToolCatalogBuilder();
        Set<ToolSchema> schemas = new LinkedHashSet<>();
        Set<String> aliases = new LinkedHashSet<>();
        JavaRecordSchemaGenerator schemasGenerator = new JavaRecordSchemaGenerator();
        for (JavaTool<?, ?> tool : tools) {
            Objects.requireNonNull(tool, "Java Tool must not be null");
            JavaToolSpec<?, ?> spec = Objects.requireNonNull(tool.spec(), "Java Tool spec must not be null");
            if (!aliases.add(spec.alias().value())) {
                throw new ProductAssemblyException(
                        "JAVA_TOOL_ALIAS_CONFLICT", "duplicate Java Tool alias was supplied");
            }
            String schemaPrefix = "urn:haifa:java-tool:" + spec.name().value();
            ToolSchema input = schemasGenerator.generate(
                    schemaPrefix + ":input", spec.version().value(), spec.inputType());
            ToolSchema output = schemasGenerator.generate(
                    schemaPrefix + ":output", spec.version().value(), spec.outputType());
            schemas.add(input);
            schemas.add(output);
            ToolDefinition definition = new ToolDefinition(
                    spec.name(),
                    spec.version(),
                    spec.providerId(),
                    spec.title(),
                    spec.description(),
                    input,
                    output,
                    ToolExecutionMode.IN_PROCESS,
                    true,
                    spec.timeout(),
                    spec.concurrencyPolicy(),
                    spec.idempotency(),
                    spec.risk(),
                    spec.sideEffects(),
                    spec.resources(),
                    spec.credentialRequirements(),
                    spec.approvalRequirement(),
                    spec.provenance(),
                    false,
                    spec.tags());
            builder.register(
                    spec.alias(),
                    definition,
                    "java-tool:" + spec.name().value() + "@" + spec.version().value(),
                    provider(tool, spec));
        }
        DefaultToolCatalog catalog = builder.freeze();
        return new RegisteredTools(catalog, new DefaultToolInvoker(catalog), Set.copyOf(schemas), Set.copyOf(aliases));
    }

    private static <I extends Record, O extends Record> ToolProvider typedProvider(
            JavaTool<I, O> tool, JavaToolSpec<I, O> spec) {
        return new ToolProvider() {
            @Override
            public io.haifa.agent.tool.api.ToolProviderId id() {
                return spec.providerId();
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
                            request.policyDecisionRef(),
                            request.cancellation(),
                            request.credentialLeases());
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
    private static ToolProvider provider(JavaTool<?, ?> tool, JavaToolSpec<?, ?> spec) {
        return typedProvider((JavaTool<Record, Record>) tool, (JavaToolSpec<Record, Record>) spec);
    }

    private static MergedPlatform merge(ToolPlatformContribution base, RegisteredTools registered) {
        List<SourceBinding> sources = new ArrayList<>();
        if (base != null) {
            base.catalog()
                    .snapshot()
                    .bindings()
                    .forEach(binding -> sources.add(new SourceBinding(binding, base.invoker())));
        }
        registered
                .catalog()
                .snapshot()
                .bindings()
                .forEach(binding -> sources.add(new SourceBinding(binding, registered.invoker())));
        sources.sort(Comparator.comparing(value -> value.binding().alias()));
        rejectDuplicates(sources);

        String digestSource = sources.stream()
                .map(value -> value.binding().alias().value() + "="
                        + value.binding().coordinate().externalForm())
                .reduce("", (left, right) -> left + "\n" + right);
        String digest =
                SdkConfigurationDigest.sha256("tool-catalog-v1", digestSource).substring("sha256:".length());
        List<FrozenToolBinding> mergedBindings = sources.stream()
                .map(source -> copyWithDigest(source.binding(), digest))
                .toList();
        MergedCatalog catalog = new MergedCatalog(digest, mergedBindings);
        MergedInvoker invoker = new MergedInvoker(catalog, sources);
        JsonSchema202012Validator recordValidator = new JsonSchema202012Validator();
        ToolSchemaValidator baseValidator = requireBaseValidator(base);
        ToolSchemaValidator validator =
                (schema, instance) -> registered.schemas().contains(schema)
                        ? recordValidator.validate(schema, instance)
                        : baseValidator.validate(schema, instance);
        ProductProviderSuitability suitability =
                base == null ? ProductProviderSuitability.PRODUCTION : base.suitability();
        SdkContributionMetadata metadata = new SdkContributionMetadata(
                COORDINATE,
                ProductCapabilities.TOOL,
                "sha256:" + digest,
                suitability,
                "Unified Tool platform with Java Tool registrations");
        ToolPlatformContribution contribution = new ToolPlatformContribution(metadata, catalog, invoker, validator);
        return new MergedPlatform(contribution);
    }

    private static ToolSchemaValidator requireBaseValidator(ToolPlatformContribution base) {
        if (base != null) return base.schemaValidator();
        return new JsonSchema202012Validator();
    }

    private static void rejectDuplicates(List<SourceBinding> sources) {
        Set<Object> aliases = new LinkedHashSet<>();
        Set<Object> coordinates = new LinkedHashSet<>();
        for (SourceBinding source : sources) {
            if (!aliases.add(source.binding().alias())) {
                throw new ProductAssemblyException(
                        "JAVA_TOOL_ALIAS_CONFLICT", "Java Tool alias conflicts with the existing Tool catalog");
            }
            if (!coordinates.add(source.binding().coordinate())) {
                throw new ProductAssemblyException(
                        "JAVA_TOOL_COORDINATE_CONFLICT",
                        "Java Tool coordinate conflicts with the existing Tool catalog");
            }
        }
    }

    private static FrozenToolBinding copyWithDigest(FrozenToolBinding binding, String digest) {
        return new FrozenToolBinding(
                binding.alias(),
                binding.coordinate(),
                binding.definition(),
                binding.providerBindingReference(),
                digest);
    }

    private static ProductProfile extendProfile(
            ProductProfile profile, ToolPlatformContribution contribution, Set<String> javaAliases) {
        Map<io.haifa.agent.sdk.product.ProductCapabilityId, ProductCapabilityRequirement> requirements =
                new LinkedHashMap<>(profile.capabilityRequirements());
        requirements.put(
                ProductCapabilities.TOOL,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.TOOL,
                        Set.of(COORDINATE),
                        profile.requirement(ProductCapabilities.TOOL).minimumSuitability()));
        Set<String> aliases = new LinkedHashSet<>(profile.allowedTools());
        aliases.addAll(javaAliases);
        return ProductProfile.create(
                profile.productId(),
                profile.productVersion(),
                profile.definitionId(),
                profile.definitionVersion(),
                profile.runProfileId(),
                profile.runProfileVersion(),
                profile.instructions(),
                profile.budget(),
                profile.limits(),
                profile.policies(),
                requirements,
                aliases,
                profile.allowedSkills(),
                profile.allowedExtensions());
    }

    public record Prepared(
            ProductProfile profile,
            List<ProductContribution> contributions,
            Map<ProductContribution, ProductContribution> lifecycleReplacements) {
        public Prepared {
            profile = Objects.requireNonNull(profile, "profile must not be null");
            contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions must not be null"));
            lifecycleReplacements =
                    Map.copyOf(Objects.requireNonNull(lifecycleReplacements, "lifecycleReplacements must not be null"));
        }
    }

    private record RegisteredTools(
            DefaultToolCatalog catalog, ToolInvoker invoker, Set<ToolSchema> schemas, Set<String> aliases) {}

    private record MergedPlatform(ToolPlatformContribution contribution) {}

    private record SourceBinding(FrozenToolBinding binding, ToolInvoker invoker) {}

    private static final class MergedCatalog implements ToolCatalog {
        private final ToolCatalogSnapshot snapshot;
        private final Map<io.haifa.agent.tool.api.ToolAlias, FrozenToolBinding> aliases;
        private final Map<io.haifa.agent.tool.api.ToolCoordinate, FrozenToolBinding> coordinates;

        private MergedCatalog(String digest, List<FrozenToolBinding> bindings) {
            snapshot = new ToolCatalogSnapshot(digest, bindings);
            Map<io.haifa.agent.tool.api.ToolAlias, FrozenToolBinding> byAlias = new LinkedHashMap<>();
            Map<io.haifa.agent.tool.api.ToolCoordinate, FrozenToolBinding> byCoordinate = new LinkedHashMap<>();
            bindings.forEach(binding -> {
                byAlias.put(binding.alias(), binding);
                byCoordinate.put(binding.coordinate(), binding);
            });
            aliases = Map.copyOf(byAlias);
            coordinates = Map.copyOf(byCoordinate);
        }

        @Override
        public ToolCatalogSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public Optional<FrozenToolBinding> findByAlias(io.haifa.agent.tool.api.ToolAlias alias) {
            return Optional.ofNullable(aliases.get(alias));
        }

        @Override
        public Optional<FrozenToolBinding> findByCoordinate(io.haifa.agent.tool.api.ToolCoordinate coordinate) {
            return Optional.ofNullable(coordinates.get(coordinate));
        }
    }

    private static final class MergedInvoker implements ToolInvoker {
        private final MergedCatalog catalog;
        private final Map<io.haifa.agent.tool.api.ToolCoordinate, SourceBinding> sources;

        private MergedInvoker(MergedCatalog catalog, List<SourceBinding> sources) {
            this.catalog = catalog;
            Map<io.haifa.agent.tool.api.ToolCoordinate, SourceBinding> byCoordinate = new LinkedHashMap<>();
            sources.forEach(source -> byCoordinate.put(source.binding().coordinate(), source));
            this.sources = Map.copyOf(byCoordinate);
        }

        @Override
        public ToolResult invoke(ToolInvocationRequest request) {
            SourceBinding source = validate(request.binding());
            ToolInvocationRequest delegated = new ToolInvocationRequest(
                    source.binding(),
                    request.toolCallId(),
                    request.runId(),
                    request.tenant(),
                    request.principal(),
                    request.arguments(),
                    request.deadline(),
                    request.idempotencyKey(),
                    request.policyDecisionRef(),
                    request.cancellation(),
                    request.credentialLeases(),
                    request.observer());
            return source.invoker().invoke(delegated);
        }

        @Override
        public void validateBinding(FrozenToolBinding binding) {
            SourceBinding source = validate(binding);
            source.invoker().validateBinding(source.binding());
        }

        private SourceBinding validate(FrozenToolBinding binding) {
            FrozenToolBinding merged = catalog.findByCoordinate(binding.coordinate())
                    .orElseThrow(() -> new ToolInvocationException("tool coordinate is not in the merged catalog"));
            if (!merged.equals(binding)) {
                throw new ToolInvocationException("tool binding differs from the merged catalog");
            }
            return Optional.ofNullable(sources.get(binding.coordinate()))
                    .orElseThrow(() -> new ToolInvocationException("tool source is not available"));
        }
    }
}
