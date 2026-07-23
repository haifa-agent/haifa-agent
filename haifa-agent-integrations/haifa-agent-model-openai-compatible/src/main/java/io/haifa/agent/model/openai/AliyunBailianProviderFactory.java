package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ProviderStatus;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Configuration factory for governed Bailian OpenAI Chat model profiles. */
public final class AliyunBailianProviderFactory {
    public static final ModelProviderId PROVIDER_ID = new ModelProviderId("aliyun-bailian");
    public static final String ADAPTER_TYPE = "openai-compatible";
    public static final String DEFAULT_REGION = "cn-beijing";
    private static final String COMPATIBLE_MODE_PATH = "/compatible-mode/v1";
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private AliyunBailianProviderFactory() {}

    public static ModelProviderDefinition provider(ProviderConfiguration configuration, List<ModelProfile> profiles) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        if (profiles == null || profiles.isEmpty()) throw new IllegalArgumentException("profiles must not be empty");
        LinkedHashMap<String, Object> providerOptions = new LinkedHashMap<>();
        providerOptions.put(OpenAiCompatibleDialects.DIALECT_ID, OpenAiCompatibleDialects.ALIYUN_BAILIAN);
        providerOptions.put(OpenAiCompatibleDialects.DIALECT_VERSION, OpenAiCompatibleDialects.VERSION_1);
        providerOptions.put("region", configuration.region());
        providerOptions.put("workspace_id", configuration.workspaceId());
        List<ModelDefinition> models =
                profiles.stream().map(ModelProfile::definition).toList();
        return new ModelProviderDefinition(
                PROVIDER_ID,
                configuration.providerVersion(),
                "Alibaba Cloud Model Studio",
                ADAPTER_TYPE,
                configuration.endpoint(),
                configuration.credentialRef(),
                ProviderStatus.ACTIVE,
                models,
                providerOptions,
                Map.of("protocol", "openai-chat-completions"));
    }

    public record ProviderConfiguration(
            String providerVersion, String workspaceId, String region, CredentialRef credentialRef) {
        public ProviderConfiguration {
            if (providerVersion == null || providerVersion.isBlank())
                throw new IllegalArgumentException("providerVersion must not be blank");
            workspaceId = dnsLabel(workspaceId, "workspaceId");
            region = region == null || region.isBlank() ? DEFAULT_REGION : dnsLabel(region, "region");
            Objects.requireNonNull(credentialRef, "credentialRef must not be null");
        }

        public ProviderConfiguration(String providerVersion, String workspaceId, CredentialRef credentialRef) {
            this(providerVersion, workspaceId, DEFAULT_REGION, credentialRef);
        }

        public URI endpoint() {
            return URI.create("https://" + workspaceId + "." + region + ".maas.aliyuncs.com" + COMPATIBLE_MODE_PATH);
        }

        public static ProviderConfiguration localExample(String workspaceId) {
            return new ProviderConfiguration(
                    "configured-v1", workspaceId, DEFAULT_REGION, new CredentialRef("env://DASHSCOPE_API_KEY"));
        }
    }

    public record ModelProfile(
            ModelDefinitionId id,
            String version,
            String providerModelId,
            String displayName,
            Set<ModelCapability> capabilities,
            int contextWindow,
            int maxOutputTokens,
            Map<String, Object> options) {
        public ModelProfile {
            Objects.requireNonNull(id, "id must not be null");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
            if (providerModelId == null || providerModelId.isBlank())
                throw new IllegalArgumentException("providerModelId must not be blank");
            if (displayName == null || displayName.isBlank())
                throw new IllegalArgumentException("displayName must not be blank");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
            options = Map.copyOf(Objects.requireNonNull(options, "options must not be null"));
        }

        private ModelDefinition definition() {
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
                    options,
                    Map.of("profile_source", "governed-configuration"));
        }
    }

    private static String dnsLabel(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!DNS_LABEL.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a valid DNS label");
        }
        return normalized;
    }
}
