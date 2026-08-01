package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.CodingModelCatalog;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ProviderStatus;
import io.haifa.agent.model.core.ImmutableModelCatalog;
import io.haifa.agent.model.core.InMemoryProviderHealthRegistry;
import io.haifa.agent.model.core.ModelAccessPolicy;
import io.haifa.agent.model.core.ModelAvailabilityRequest;
import io.haifa.agent.model.core.ModelSelectionRequest;
import io.haifa.agent.model.core.StaticModelPlatform;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** CLI product adapter over model-core's immutable, deterministic model platform. */
final class CliCodingModelCatalog implements CodingModelCatalog {
    private static final Set<ModelCapability> REQUIRED =
            EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING);

    private final String defaultModelId;
    private final StaticModelPlatform platform;

    CliCodingModelCatalog(CliConfiguration configuration) {
        defaultModelId = configuration.model().id();
        Map<String, List<CliConfiguration.Model>> grouped = new LinkedHashMap<>();
        configuration.availableModels().forEach(model -> grouped.computeIfAbsent(
                        model.providerId(), ignored -> new ArrayList<>())
                .add(model));
        List<ModelProviderDefinition> providers = grouped.entrySet().stream()
                .map(entry -> provider(entry.getKey(), entry.getValue()))
                .toList();
        platform = new StaticModelPlatform(
                new ImmutableModelCatalog(providers),
                ModelAccessPolicy.allowAll(),
                Map.of("openai-compatible", "1.0.0"),
                new InMemoryProviderHealthRegistry());
    }

    @Override
    public String defaultModelId() {
        return defaultModelId;
    }

    @Override
    public List<CodingModelOption> available(TenantRef tenant, PrincipalRef principal) {
        return platform.listAvailable(new ModelAvailabilityRequest(tenant, principal, REQUIRED)).stream()
                .flatMap(provider -> provider.models().stream()
                        .map(model -> new CodingModelOption(
                                model.id().value(),
                                model.displayName(),
                                provider.id().value(),
                                provider.displayName(),
                                model.capabilities().stream()
                                        .map(Enum::name)
                                        .collect(java.util.stream.Collectors.toSet()),
                                model.contextWindow())))
                .toList();
    }

    @Override
    public Optional<CodingModelOption> find(TenantRef tenant, PrincipalRef principal, String modelId) {
        Optional<CodingModelOption> option = available(tenant, principal).stream()
                .filter(value -> value.id().equals(modelId))
                .findFirst();
        option.ifPresent(ignored -> platform.select(
                new ModelSelectionRequest(tenant, principal, new ModelDefinitionId(modelId), REQUIRED)));
        return option;
    }

    private static ModelProviderDefinition provider(String providerId, List<CliConfiguration.Model> models) {
        CliConfiguration.Model first = models.getFirst();
        var providerSnapshot = LocalCodingAgent.modelSnapshot(first);
        if (models.stream()
                .anyMatch(model -> !model.endpoint().equals(first.endpoint())
                        || !model.credentialRef().equals(first.credentialRef()))) {
            throw new IllegalArgumentException("models for one provider must share endpoint and credentialRef");
        }
        ModelProviderId id = new ModelProviderId(providerId);
        List<ModelDefinition> definitions = models.stream()
                .map(model -> new ModelDefinition(
                        new ModelDefinitionId(model.id()),
                        "cli-v1",
                        id,
                        model.modelId(),
                        model.displayName(),
                        ModelStatus.ACTIVE,
                        capabilities(model),
                        131_072,
                        8_192,
                        LocalCodingAgent.modelSnapshot(model).invocationOptions(),
                        Map.of()))
                .toList();
        return new ModelProviderDefinition(
                id,
                "cli-v1",
                first.providerDisplayName(),
                "openai-compatible",
                first.endpoint(),
                new io.haifa.agent.model.api.CredentialRef(first.credentialRef()),
                ProviderStatus.ACTIVE,
                definitions,
                providerSnapshot.providerOptions(),
                Map.of());
    }

    private static Set<ModelCapability> capabilities(CliConfiguration.Model model) {
        EnumSet<ModelCapability> capabilities =
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.STRUCTURED_OUTPUT);
        if (model.providerId().equals("deepseek")) capabilities.add(ModelCapability.REASONING);
        return capabilities;
    }
}
