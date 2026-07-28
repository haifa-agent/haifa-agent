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
        JsonNode maximumPayloadBytes = root.path("persistence").path("maximumPayloadBytes");
        if (maximumPayloadBytes.isMissingNode()) return;
        if (!maximumPayloadBytes.isIntegralNumber()
                || !maximumPayloadBytes.canConvertToInt()
                || maximumPayloadBytes.intValue() < 1
                || maximumPayloadBytes.intValue() > MAX_PROTECTED_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("persistence.maximumPayloadBytes must be between 1 and "
                    + MAX_PROTECTED_PAYLOAD_BYTES
                    + ": "
                    + configRoot.relativize(path).toString().replace('\\', '/'));
        }
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
