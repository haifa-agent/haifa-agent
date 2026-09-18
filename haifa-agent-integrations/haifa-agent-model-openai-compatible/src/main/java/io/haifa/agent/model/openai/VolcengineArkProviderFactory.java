package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelReferenceKind;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ProviderStatus;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Configuration factory for governed Volcengine Ark model or endpoint bindings. */
public final class VolcengineArkProviderFactory {
    public static final ModelProviderId PROVIDER_ID = new ModelProviderId("volcengine-ark");
    public static final String ADAPTER_TYPE = "openai-compatible";

    private VolcengineArkProviderFactory() {}

    public static ModelProviderDefinition provider(ProviderConfiguration configuration, List<ModelProfile> profiles) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        if (profiles == null || profiles.isEmpty()) throw new IllegalArgumentException("profiles must not be empty");
        LinkedHashMap<String, Object> providerOptions = new LinkedHashMap<>();
        providerOptions.put("region", configuration.region());
        providerOptions.put("endpoint_host", configuration.endpointHost());
        return new ModelProviderDefinition(
                PROVIDER_ID,
                configuration.providerVersion(),
                "Volcengine Ark",
                configuration.endpoint(),
                configuration.credentialRef(),
                true,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS, OpenAiCompatibleDialects.VOLCENGINE_ARK)),
                profiles.stream().map(ModelProfile::definition).toList(),
                providerOptions,
                Map.of("protocol", "openai-chat-completions"));
    }

    public record ProviderConfiguration(
            String providerVersion, URI endpoint, String region, String endpointHost, CredentialRef credentialRef) {
        public ProviderConfiguration {
            requireText(providerVersion, "providerVersion");
            Objects.requireNonNull(endpoint, "endpoint must not be null");
            requireText(region, "region");
            requireText(endpointHost, "endpointHost");
            Objects.requireNonNull(credentialRef, "credentialRef must not be null");
        }

        public static ProviderConfiguration beijingExample() {
            return new ProviderConfiguration(
                    "configured-v1",
                    URI.create("https://ark.cn-beijing.volces.com/api/v3"),
                    "cn-beijing",
                    "ark.cn-beijing.volces.com",
                    new CredentialRef("env://ARK_API_KEY"));
        }
    }

    public record ModelProfile(
            ModelDefinitionId id,
            String version,
            String providerModelId,
            String displayName,
            ModelReferenceKind referenceKind,
            Set<ModelCapability> capabilities,
            int contextWindow,
            int maxOutputTokens,
            Map<String, Object> options) {
        public ModelProfile {
            Objects.requireNonNull(id, "id must not be null");
            requireText(version, "version");
            requireText(providerModelId, "providerModelId");
            requireText(displayName, "displayName");
            Objects.requireNonNull(referenceKind, "referenceKind must not be null");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
            options = Map.copyOf(Objects.requireNonNull(options, "options must not be null"));
        }

        private ModelDefinition definition() {
            LinkedHashMap<String, Object> frozen = new LinkedHashMap<>(options);
            frozen.put("model_reference_kind", referenceKind.name());
            return new ModelDefinition(
                    id,
                    version,
                    PROVIDER_ID,
                    providerModelId,
                    displayName,
                    ModelStatus.ACTIVE,
                    capabilities,
                    contextWindow,
                    maxOutputTokens,
                    frozen,
                    Map.of("profile_source", "governed-configuration"),
                    ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
