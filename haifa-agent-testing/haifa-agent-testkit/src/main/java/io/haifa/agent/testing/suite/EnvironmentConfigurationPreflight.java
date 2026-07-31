package io.haifa.agent.testing.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Validates private environment configuration against product startup invariants. */
final class EnvironmentConfigurationPreflight {
    private static final int MAX_PROTECTED_PAYLOAD_BYTES = 1_048_576;

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    void validate(Path configRoot) throws IOException {
        Path environments = configRoot.resolve("environments");
        if (!Files.isDirectory(environments)) return;
        try (Stream<Path> paths = Files.walk(environments)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(EnvironmentConfigurationPreflight::isYaml)
                    .sorted(Comparator.naturalOrder())
                    .toList()) {
                validateFile(configRoot, path);
            }
        }
    }

    private void validateFile(Path configRoot, Path path) throws IOException {
        JsonNode root = yaml.readTree(path.toFile());
        String relative = configRoot.relativize(path).toString().replace('\\', '/');
        validateModels(root, relative);
        JsonNode maximumPayloadBytes = root.path("persistence").path("maximumPayloadBytes");
        if (maximumPayloadBytes.isMissingNode()) return;
        if (!maximumPayloadBytes.isIntegralNumber()
                || !maximumPayloadBytes.canConvertToInt()
                || maximumPayloadBytes.intValue() < 1
                || maximumPayloadBytes.intValue() > MAX_PROTECTED_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("persistence.maximumPayloadBytes must be between 1 and "
                    + MAX_PROTECTED_PAYLOAD_BYTES
                    + ": "
                    + relative);
        }
    }

    private static void validateModels(JsonNode root, String relative) {
        if (root.has("model")) {
            throw new IllegalArgumentException(
                    "legacy model configuration is unsupported; use models.providers and models.default: " + relative);
        }
        JsonNode models = root.path("models");
        if (models.isMissingNode()) return;
        String selected = requiredText(models, "default", relative);
        JsonNode providers = models.path("providers");
        if (!providers.isArray() || providers.isEmpty()) {
            throw new IllegalArgumentException("models.providers must be a non-empty array: " + relative);
        }
        boolean selectedFound = false;
        for (JsonNode provider : providers) {
            requiredText(provider, "id", relative);
            requiredText(provider, "endpoint", relative);
            String credential = requiredText(provider, "credentialRef", relative);
            if (!credential.startsWith("env://") || credential.length() == "env://".length()) {
                throw new IllegalArgumentException("models provider credentialRef must use env://NAME: " + relative);
            }
            JsonNode providerModels = provider.path("models");
            if (!providerModels.isArray() || providerModels.isEmpty()) {
                throw new IllegalArgumentException("models provider must declare models: " + relative);
            }
            for (JsonNode model : providerModels) {
                String modelId = requiredText(model, "id", relative);
                requiredText(model, "providerModelId", relative);
                selectedFound |= selected.equals(modelId);
            }
        }
        if (!selectedFound) {
            throw new IllegalArgumentException("models.default must reference a declared model id: " + relative);
        }
    }

    private static String requiredText(JsonNode parent, String field, String relative) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string: " + relative);
        }
        return value.asText();
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".yaml.template");
    }
}
