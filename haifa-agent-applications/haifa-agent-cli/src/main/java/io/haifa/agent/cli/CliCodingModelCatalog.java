package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.CodingModelCatalog;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.model.anthropic.AnthropicModelProfileFactory;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelBindingConsistencyValidator;
import io.haifa.agent.model.api.ModelBindingProfile;
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
import io.haifa.agent.model.gemini.GeminiModelProfileFactory;
import io.haifa.agent.model.openai.OpenAiCompatibleModelProfileFactory;
import java.time.LocalDate;
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
    private final Map<String, ModelBindingProfile> profiles;
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
        profiles = configuration.availableModels().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(CliConfiguration.Model::id, model -> {
                    var snapshot = LocalCodingAgent.modelSnapshot(model);
                    if (ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT.equals(snapshot.apiStyle())) {
                        return GeminiModelProfileFactory.fromSnapshot(snapshot, LocalDate.of(2026, 8, 24));
                    } else if (ModelApiStyles.ANTHROPIC_MESSAGES.equals(snapshot.apiStyle())) {
                        return AnthropicModelProfileFactory.fromSnapshot(snapshot, LocalDate.of(2026, 8, 30));
                    } else {
                        return OpenAiCompatibleModelProfileFactory.fromSnapshot(snapshot, LocalDate.of(2026, 8, 13));
                    }
                }));
        for (ModelProviderDefinition provider : providers) {
            ModelBindingConsistencyValidator.validateAll(provider, profiles);
        }
        platform = new StaticModelPlatform(
                new ImmutableModelCatalog(providers),
                ModelAccessPolicy.allowAll(),
                Map.of(
                        ModelApiStyles.OPENAI_CHAT_ADAPTER, "1.0.0",
                        ModelApiStyles.OPENAI_RESPONSES_ADAPTER, "1.0.0",
                        ModelApiStyles.ANTHROPIC_MESSAGES_ADAPTER, "1.0.0",
                        ModelApiStyles.GOOGLE_GEMINI_ADAPTER, "1.0.0"),
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
                        .filter(model -> Optional.ofNullable(
                                        profiles.get(model.id().value()))
                                .map(ModelBindingProfile::selectable)
                                .orElse(false))
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
                .anyMatch(model -> !model.providerEndpoint().equals(first.providerEndpoint())
                        || !model.credentialRef().equals(first.credentialRef())
                        || model.nativeStreaming() != first.nativeStreaming())) {
            throw new IllegalArgumentException(
                    "models for one provider must share endpoint, credentialRef, and nativeStreaming");
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
                        model.capabilities(),
                        model.contextWindow(),
                        model.maxOutputTokens(),
                        LocalCodingAgent.modelSnapshot(model).invocationOptions(),
                        Map.of(),
                        model.style()))
                .toList();
        List<ModelApiBindingDefinition> bindings = models.stream()
                .collect(java.util.stream.Collectors.toMap(
                        CliConfiguration.Model::style,
                        model -> new ModelApiBindingDefinition(
                                model.style(),
                                model.dialect(),
                                model.endpoint().equals(model.providerEndpoint()) ? null : model.endpoint()),
                        (left, right) -> {
                            if (!left.equals(right)) {
                                throw new IllegalArgumentException("one API style must resolve to one binding");
                            }
                            return left;
                        },
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        return new ModelProviderDefinition(
                id,
                "cli-v1",
                first.providerDisplayName(),
                first.providerEndpoint(),
                new io.haifa.agent.model.api.CredentialRef(first.credentialRef()),
                first.nativeStreaming(),
                ProviderStatus.ACTIVE,
                bindings,
                definitions,
                providerSnapshot.providerOptions(),
                Map.of());
    }
}
